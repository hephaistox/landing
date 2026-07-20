(ns landing.agora.document.store
  "Shared adapter for AGORA_DOCUMENT — the single polymorphic table holding every object
  type (all document types today; objections later). SQL + Caffeine caches around the pure
  domain (landing.agora.document.identity).

  A row keeps only the identity columns — `id`, `type` (object type, the T), `name`,
  `lang`, `major`, `minor` — plus two EDN blobs: immutable `content` and mutable
  `computed` ({:pins {tnlr-key → id}}). The **raw SQL** lives one layer down in
  `landing.agora.document.db-store` (queries move there one at a time); this namespace is the
  **Caffeine cache + write orchestration** over it, and `landing.agora.document` composes on top."
  (:require
   [landing.agora.cache             :as cache]
   [landing.agora.db                :as db]
   [landing.agora.document.db-store :as dbs
                                    :refer
                                    [decode-content decode-pins encode-pins kebab q! q1! t->s]]
   [landing.agora.document.identity :as di]
   [landing.agora.document.lineage  :as lineage]
   [landing.language                :as language]))

;; --- id → document (cached): the raw `dbs/load-document` behind a Caffeine cache

(def ^:private document-cache
  "id → document (identity + immutable content + resolved pins)."
  (cache/loading 20000 dbs/load-document))

(defn fetch-document "The document for `id` (cached), or nil." [id] (cache/fetch document-cache id))

;; --- Lineage indexes (cached): successors, versions, translations.

(def ^:private successors-cache
  (cache/loading
   20000
   (fn [[ty nm lang major]]
     (mapv
      :successor-id
      (q!
       db/ds
       ["SELECT successor_id FROM AGORA_SUCCESSOR
          WHERE input_type = ? AND input_name = ? AND input_lang = ? AND input_major = ?"
        (t->s ty)
        nm
        lang
        major]
       kebab)))))

(defn successors-of
  "ids of documents that declare `tnlr-key` as an input."
  [tnlr-key]
  (cache/fetch successors-cache tnlr-key))

(def ^:private documents-cache
  (cache/loading 20000 (fn [[ty nm lang major]] (dbs/documents ty nm lang major))))

(defn documents
  "All versions (minors) of a TNLR lineage — `[{:id :minor :draft :publication-id} …]` ascending,
  drafts included — cached. This is 'download the lineage'; the caller resolves *which* version it
  wants with the `lineage` rules (`latest-published`, `latest-with-drafts`, `draft-in-publication`)
  — resolution is Clojure's job, this only fetches."
  [ty nm lang major]
  (cache/fetch documents-cache [ty nm lang major]))

(def ^:private translations-cache
  (cache/loading
   20000
   (fn [[ty nm lang]]
     (q!
      db/ds
      ["SELECT DISTINCT name, major, lang FROM AGORA_DOCUMENT
         WHERE type = ? AND name = ? AND lang <> ? AND draft = 0 ORDER BY lang"
       (t->s ty)
       nm
       lang]
      kebab))))

(defn translations-of
  "other-language versions {:name :major :lang} of a concept."
  [ty nm lang]
  (cache/fetch translations-cache [ty nm lang]))

;; --- Resolution: fetch the lineage (`documents`), let `lineage` pick. One implementation, in
;; Clojure — the SQL never chooses a version. Cross-language fallback (a lineage not translated
;; into `lang`) is a second `documents` call for the default language (rare).

(defn latest-with-drafts
  "The current version among a lineage's `minors` — the highest minor, drafts included (the
  owner/working view). The store's own copy: the domain (`lineage`) keeps it private."
  [minors]
  (when (seq minors) (apply max-key :minor minors)))

(defn resolve-latest-id
  "The id of the latest **published** minor of (type, name, major) in `lang`; if that lineage has
  no version in `lang`, a second lookup falls back to the **default** language. Drafts excluded —
  the single lever behind permalinks, input/cite edges, successors and search."
  [ty nm major lang]
  (or (:id (lineage/latest-published (documents ty nm lang major)))
      (when (not= lang language/default-lang)
        (:id (lineage/latest-published (documents ty nm language/default-lang major))))))

(defn resolve-latest-any-id
  "The id of the highest-minor version of (type, name, major) in `lang` — **drafts included** (the
  current version even if unpublished). Owner-facing (e.g. a publication, `draft` while open)."
  [ty nm major lang]
  (:id (latest-with-drafts (documents ty nm lang major))))

(defn- draft-in-publication*
  "The lineage's own **draft in publication `pub-id`** — its latest draft minor tagged with that
  publication — or nil. Pure over the lineage's `minors` (each carrying `:draft`/`:publication-id`);
  the holder fetches the minors, this rule picks."
  [minors pub-id]
  (latest-with-drafts (filter #(and (:draft %) (= (:publication-id %) pub-id)) minors)))

(defn draft-in-publication
  "The id of publication `pub-id`'s own draft of lineage (type, name, major) in `lang`, or nil."
  [pub-id ty nm major lang]
  (:id (draft-in-publication* (documents ty nm lang major) pub-id)))

(defn resolve-in-publication
  "Resolve a TNLR within publication `pub-id`: the publication's own draft of that lineage if it
  has one, else the latest **published** minor (classical). Used while a publication is open —
  public reads use `resolve-latest-id`."
  [pub-id ty nm major lang]
  (or (draft-in-publication pub-id ty nm major lang) (resolve-latest-id ty nm major lang)))

;; --- pure change-model rules (the publish lifecycle + its invariant), relocated from the domain ---
;; Kept in Clojure — the publication/change model lives here, not in the shared cljc lineage layer.

(defn pending?
  "The `inputs` (declared TNLRs) whose lineage has **no published version** — `published?` is a
  predicate `tnlr → boolean`. These break the publish invariant (a public node may not depend on a
  draft), so `publish` must refuse until they are published. Pure — the caller supplies `published?`."
  [inputs published?]
  (filterv #(not (published? %)) inputs))

(defn publish
  "**Close=publish** a publication: `publication` becomes closed + published, and **every draft it
  gathers** (`members`) becomes published (`:draft false`), **all at once**. Publishing together keeps
  the invariant that no published version depends on a draft, so the flip is order-independent.
  Returns `[publication & members]`, all published; the caller persists (and, in the DB, prunes each
  lineage's intermediate drafts)."
  [publication members]
  (into [(assoc publication :status "closed" :draft false)] (map #(assoc % :draft false)) members))

(defn- latest-of
  "The domain's injected `latest-of`: an input TNLR → the id it should pin to. Latest **published**
  (cross-lang), else the latest version **including drafts** in this lang — so a draft's inputs pin
  to a concrete id and render rather than dangling. This draft fallback can only bite an input of an
  *unpublished* document (the publish invariant), so it never surfaces a draft in a public view."
  [{ty :type
    nm :name
    major :major
    lang :lang}]
  (or (resolve-latest-id ty nm major lang) (:id (latest-with-drafts (documents ty nm lang major)))))

(defn- latest-of-in
  "Pin resolver scoped to publication `pub-id`: an input TNLR → the id to pin, **preferring the
  publication's own draft** of that lineage over the published version."
  [pub-id {ty :type
           nm :name
           major :major
           lang :lang}]
  (resolve-in-publication pub-id ty nm major lang))

;; --- Cache invalidation

(defn evict-lineage!
  [ty nm lang major]
  (cache/evict! documents-cache [ty nm lang major])
  (cache/evict! translations-cache [ty nm lang]))

(defn clear-caches!
  []
  (cache/clear! document-cache)
  (cache/clear! successors-cache)
  (cache/clear! documents-cache)
  (cache/clear! translations-cache))

;; --- Successor index (reverse edges) + re-pin

(defn index-successors!
  "Record, in AGORA_SUCCESSOR, that `successor-id` declares each of `tnlrs` as input."
  [successor-id tnlrs]
  (doseq [{:keys [tnlr]} (di/successor-tuples successor-id tnlrs)]
    (let [[ty nm lang major] (di/tnlr-key tnlr)]
      (q!
       db/ds
       ["INSERT IGNORE INTO AGORA_SUCCESSOR
          (input_type, input_name, input_lang, input_major, successor_id) VALUES (?, ?, ?, ?, ?)"
        (t->s ty)
        nm
        lang
        major
        successor-id])
      (cache/evict! successors-cache [ty nm lang major]))))

(defn repin-successors!
  "A new minor `new-id` was created for concept (type,name,lang,major): re-point every
  successor's pin for that TNLR to `new-id` (a mutable `computed` update — no version),
  and drop their cached documents."
  [ty nm lang major new-id]
  (let [t {:type ty
           :name nm
           :lang lang
           :major major}]
    (doseq [sid (cache/fetch successors-cache (di/tnlr-key t))]
      (when-let [row (q1! db/ds ["SELECT computed FROM AGORA_DOCUMENT WHERE id = ?" sid] kebab)]
        (let [pins' (di/repin (decode-pins (:computed row)) t new-id)]
          (q! db/ds ["UPDATE AGORA_DOCUMENT SET computed = ? WHERE id = ?" (encode-pins pins') sid])
          (cache/evict! document-cache sid))))))

(defn publish!
  "Promote version `id` of lineage (type,name,lang,major) to published: clear its draft flag,
  delete the lineage's remaining **intermediate draft** minors (and their now-orphan successor
  rows), then re-point successors onto this — now the resolved — version. Published minors are
  kept (version history); only the drafts you iterated through are pruned. Clears the caches."
  [ty nm lang major id]
  ;; 1. publish the target version first, so it survives the draft prune below
  (q! db/ds ["UPDATE AGORA_DOCUMENT SET draft = 0 WHERE id = ?" id])
  ;; 2. drop the successor-index rows of the intermediate drafts (else they dangle), then the drafts
  (q!
   db/ds
   ["DELETE FROM AGORA_SUCCESSOR WHERE successor_id IN
                (SELECT id FROM AGORA_DOCUMENT
                  WHERE type = ? AND name = ? AND lang = ? AND major = ? AND draft = 1)"
    (t->s ty)
    nm
    lang
    major])
  (q!
   db/ds
   ["DELETE FROM AGORA_DOCUMENT
                WHERE type = ? AND name = ? AND lang = ? AND major = ? AND draft = 1"
    (t->s ty)
    nm
    lang
    major])
  ;; 3. successors now resolve to (and pin onto) the newly-published minor
  (repin-successors! ty nm lang major id)
  (clear-caches!))

;; --- Write

(defn author-name
  [owner-id]
  (when owner-id
    (:display-name
     (q1! db/ds ["SELECT display_name FROM AGORA_USER WHERE id = ?" owner-id] kebab))))

(defn next-minor
  [ty nm lang major]
  (:m
   (q1!
    db/ds
    ["SELECT COALESCE(MAX(minor) + 1, 0) AS m FROM AGORA_DOCUMENT
       WHERE type = ? AND name = ? AND lang = ? AND major = ?"
     (t->s ty)
     nm
     lang
     major]
    kebab)))

(defn lang-exists?
  [ty nm major lang]
  (some?
   (q1!
    db/ds
    ["SELECT id FROM AGORA_DOCUMENT WHERE type = ? AND name = ? AND major = ? AND lang = ? LIMIT 1"
     (t->s ty)
     nm
     major
     lang]
    kebab)))

(defn cid-taken?
  "True when any document already uses `cid` as its `name` (identity key) — so a freshly
  generated cid can be checked for the (negligible) chance of collision."
  [cid]
  (some? (q1! db/ds ["SELECT id FROM AGORA_DOCUMENT WHERE name = ? LIMIT 1" cid] kebab)))

(defn insert-document!
  "Insert one immutable version: **resolve** the declared inputs' pins, write the row (raw persist
  in `dbs/insert-row!`), then **index** the declared inputs as successors. `ident` = {:id :type
  :name :lang :major :minor :draft? :publication-id}; `content` is the immutable map (incl. its
  declared `:inputs`). Pins resolve to the publication's own drafts while authoring in one, else
  classically. `:draft?` (default false = published) marks an unpublished draft — excluded from
  resolution/discovery until Publish clears it."
  [{:keys [id publication-id]
    :as ident}
   content]
  (let [tnlrs (:inputs content)
        pins (di/pin-all tnlrs (if publication-id (partial latest-of-in publication-id) latest-of))]
    (dbs/insert-row! ident content pins)
    (index-successors! id tnlrs)))

(defn backfill-published-at!
  "One-off: populate the denormalized `published_at` column from each row's
  `content.:published-at`. Run once from a REPL after applying migration 004; new writes
  set it directly. Idempotent — only touches rows where the column is still NULL. Returns
  the number of rows updated."
  []
  (reduce (fn [n {:keys [id content]}]
            (if-let [p (:published-at (decode-content content))]
              (do (q! db/ds ["UPDATE AGORA_DOCUMENT SET published_at = ? WHERE id = ?" p id])
                  (inc n))
              n))
          0
          (q! db/ds ["SELECT id, content FROM AGORA_DOCUMENT WHERE published_at IS NULL"] kebab)))

(defn rebuild-successor-index!
  "Recompute AGORA_SUCCESSOR from every document's declared inputs, and drop the in-memory
  caches. Safe to run any time; run daily to heal drift."
  []
  (q! db/ds ["DELETE FROM AGORA_SUCCESSOR"])
  (doseq [{:keys [id content]} (q! db/ds ["SELECT id, content FROM AGORA_DOCUMENT"] kebab)]
    (index-successors! id (:inputs (decode-content content))))
  (clear-caches!)
  :ok)

(defn rebuild-pins!
  "Recompute every document's `computed.:pins` from its declared `content.:inputs` — exactly
  what `insert-document!` does on write — healing any pin drift (e.g. a successor whose
  `repin-successors!` was missed while the reverse index was stale). Independent of
  AGORA_SUCCESSOR: each doc's pins are resolved straight from its own inputs via `latest-of`,
  so it needs no prior successor rebuild. Only rows whose pins actually change are written.
  Drops the caches. Returns the number of documents re-pinned."
  []
  (let [changed (reduce (fn [n {:keys [id content computed]}]
                          (let [pins (decode-pins computed)
                                pins' (di/pin-all (:inputs (decode-content content)) latest-of)]
                            (if (= pins pins')
                              n
                              (do (q! db/ds
                                      ["UPDATE AGORA_DOCUMENT SET computed = ? WHERE id = ?"
                                       (encode-pins pins')
                                       id])
                                  (inc n)))))
                        0
                        (q! db/ds ["SELECT id, content, computed FROM AGORA_DOCUMENT"] kebab))]
    (clear-caches!)
    changed))
