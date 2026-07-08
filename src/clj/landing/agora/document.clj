(ns landing.agora.document
  "The single engine for versioned AGORA_DOCUMENT documents. KIs, articles (and future
  types) are all `type` rows here, sharing one implementation: create / edit /
  fetch, explicit inputs (add / drop), translate, search, discovery, admin and
  sitemap are all generic. Per-type differences are parameters in `types` — the
  place to add a flag when a new behavioural difference appears.

  All graph decisions live in the domain; this ns only does the type-parameterised
  I/O on top of the shared node primitives (landing.agora.node)."
  (:require
   [clojure.string       :as str]
   [landing.agora.db     :as db]
   [landing.agora.document-domain :as domain]
   [landing.agora.node   :as node]
   [landing.language     :as language])
  (:import (java.text Normalizer Normalizer$Form)))

(def types
  "The registry of object types. Every feature is available to every type; entries only
  capture where behaviour genuinely differs (currently nothing — KIs and articles are
  the same engine, differing only in UI vocabulary). Add a type by adding an entry."
  {"ki" {}
   "article" {}})

;; Every document's prose lives under one content key, `:text` — a KI's "statement" and
;; an article's "body" were the same slot under two names; they are unified here. A
;; node's inputs ARE the `[[ki:…]]` citations in that text (parsed on write), so citing
;; a KI inline is how you formalise an input edge.
(defn text-of
  "A document's prose, from the unified `:text` key — falling back to the legacy
  per-type keys (`:statement`/`:body`) for rows written before the fields were unified.
  Those rows migrate to `:text` on their next write (see `normalize-text`)."
  [content]
  (or (:text content) (:statement content) (:body content)))

(defn- normalize-text
  "Fold any prose (`:text`/legacy `:statement`/`:body`) into the single `:text` key and
  drop the legacy ones, so every written version stores prose under exactly one key."
  [content]
  (if-let [t (text-of content)]
    (-> content (assoc :text t) (dissoc :statement :body))
    content))

(def ^:private carried
  "Immutable content keys carried forward on a new minor (everything authored;
  `:published-at` is re-stamped each version). The legacy `:statement`/`:body` are kept
  so a not-yet-migrated old row carries its prose forward — `normalize-text` then folds
  it into `:text` on write."
  [:kind :title :text :statement :body :author :owner-id :inputs :source])

;; ---------------------------------------------------------------------------
;; Read — the endpoint-facing view (generic across types)
;; ---------------------------------------------------------------------------

(defn view
  "Endpoint view of an already-fetched node `n`: resolved input refs, successor ids,
  version lineage and translations. `:pins` is dropped; `:owner-id` is renamed to the
  public `:author-id` (the owning account, used to link the author badge to its profile
  page) — nil for unowned/seeded documents. Uniform across types."
  [n]
  (let [tnlr (domain/tnlr-key n)
        inputs (domain/input-refs (:inputs n) (:pins n))]
    (-> n
        (assoc :inputs inputs
               :author-id (:owner-id n)
               :successors (mapv (fn [sid] {:id sid}) (node/successors-of tnlr))
               :versions (node/versions-of tnlr)
               :translations (node/translations-of (:type n) (:name n) (:lang n)))
        (dissoc :owner-id :pins))))

(defn fetch
  "The document `id` as the endpoint view, or nil if unknown."
  [id]
  (when-let [n (node/fetch-node id)] (view n)))

(defn fetch-by-major
  "The latest-minor document of (type, name, major) in `lang` (cross-language
  fallback) — the permanent public identity — or nil."
  [type name major lang]
  (when-let [id (node/resolve-latest-id type name major lang)] (fetch id)))

;; ---------------------------------------------------------------------------
;; Write
;; ---------------------------------------------------------------------------

(defn- inputs-of
  "The inputs for a document: the `[[ki:…]]` citations parsed from its `:text`. Inputs
  are exactly the in-text citations, so citing a KI inline formalises an input edge."
  [_type content _declared lang]
  (domain/cite-refs (or (text-of content) "") lang))

(defn slugify
  "A URL/identity slug from a title: `\"Le paradis\"` → `\"le-paradis\"`. Accents are
  stripped, everything non-alphanumeric becomes a single `-`. Blank → \"untitled\"."
  [s]
  (let [base (-> (Normalizer/normalize (or s "") Normalizer$Form/NFD)
                 (str/replace #"\p{M}+" "")
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"(^-+)|(-+$)" ""))]
    (if (str/blank? base) "untitled" base)))

(defn- name-exists?
  "True when a lineage (type, name, lang, major 1) already exists — i.e. the slug is
  taken in this language."
  [type name lang]
  (some? (node/resolve-latest-id type name 1 lang)))

(defn unique-name
  "`base` if free (in this type+lang at major 1), else `base-2`, `base-3`… — so a
  generated slug never clashes with an existing lineage."
  [type base lang]
  (if-not (name-exists? type base lang)
    base
    (loop [i 2]
      (let [candidate (str base "-" i)]
        (if (name-exists? type candidate lang) (recur (inc i)) candidate)))))

(defn- new-minor!
  "Insert a new minor of the concept `src` with `content` (its declared `:inputs` are
  stored as given), re-pin successors onto it, return the new document view."
  [src content]
  (let [{:keys [type name lang major]} src
        new-id (node/uuid)]
    (node/insert-node! {:id new-id
                        :type type
                        :name name
                        :lang lang
                        :major major
                        :minor (node/next-minor type name lang major)}
                       (normalize-text (assoc content :published-at (node/now-iso))))
    (node/repin-successors! type name lang major new-id)
    (node/evict-lineage! type name lang major)
    (fetch new-id)))

(defn create
  "Create a brand-new document (major 1, minor 0) of `type`. `content` holds the
  authored fields (`:title`, `:lang`, `:text`, and `:kind` for KIs). The identity `name`
  is derived from the title (slug, de-clashed) unless one is given explicitly. Inputs
  are derived from the `:text` citations. Returns the view."
  [type
   owner-id
   {:keys [name lang title]
    :as content}]
  (let [id (node/uuid)
        lang (or lang language/default-lang)
        name (unique-name type (if (str/blank? name) (slugify title) name) lang)
        inputs (inputs-of type content (:inputs content) lang)]
    (node/insert-node! {:id id
                        :type type
                        :name name
                        :lang lang
                        :major 1
                        :minor 0}
                       (normalize-text
                        (-> content
                            (dissoc :name :lang)
                            (assoc :inputs inputs
                                   :author (node/author-name owner-id)
                                   :owner-id owner-id
                                   :published-at (node/now-iso)))))
    (node/evict-lineage! type name lang 1)
    (fetch id)))

(defn edit
  "Edit document `id` → a new minor. `changes` overrides authored fields (nils are
  ignored → the old value is kept). Inputs are carried forward, or re-derived from
  the body for :inputs-from-body types. nil if `id` is unknown."
  [id owner-id changes]
  (when-let [src (node/fetch-node id)]
    (let [merged (merge (select-keys src carried)
                        (into {} (remove (comp nil? val) changes))
                        {:author (node/author-name owner-id)
                         :owner-id owner-id})
          inputs (inputs-of (:type src) merged (:inputs merged) (:lang src))]
      (new-minor! src (assoc merged :inputs inputs)))))

(defn add-input
  "Declare the document referenced by `input` (name+major, same language) as an input
  of `id` → a new minor. Idempotent; `:input-limit` if the cap is exceeded. Works for
  any type (sets inputs explicitly, bypassing body derivation)."
  [id
   owner-id
   {in-name :name
    in-major :major}]
  (when-let [{:keys [type lang]
              :as src}
             (node/fetch-node id)]
    (if (node/resolve-latest-id type in-name in-major lang)
      (let [t {:type type
               :name in-name
               :lang lang
               :major in-major}
            inputs (domain/add-declared (:inputs src) t)]
        (if (> (count inputs) domain/max-inputs)
          :input-limit
          (new-minor! src
                      (merge (select-keys src carried)
                             {:inputs inputs
                              :author (node/author-name owner-id)
                              :owner-id owner-id}))))
      (fetch id))))

(defn drop-input
  "Remove the declared input `input` (name+major) from document `id` → a new minor.
  Inputs are derived from the text, so we also **strip the matching `[[ki:…]]` citation**
  from the text field (keeping its label as plain prose): the inline mention stays
  readable, the edge is gone, and the next edit's re-derive can't bring it back. This is
  what makes removal a first-class input-field action rather than hand-editing the text."
  [id
   owner-id
   {in-name :name
    in-major :major}]
  (when-let [{:keys [type lang]
              :as src}
             (node/fetch-node id)]
    (let [t    {:type type
                :name in-name
                :lang lang
                :major in-major}
          text (text-of src)]
      (new-minor! src
                  (cond-> (merge (select-keys src carried)
                                 {:inputs (domain/drop-declared (:inputs src) t)
                                  :author (node/author-name owner-id)
                                  :owner-id owner-id})
                    text (assoc :text (domain/strip-cite text in-name in-major)))))))

(defn translate
  "Create a `to-lang` version of document `id` and to-lang copies of its direct
  inputs. `overrides` (nil-safe) replaces authored fields on the new version.
  Existing versions are untouched. Returns the to-lang document view."
  [id to-lang owner-id overrides]
  (when-let [{:keys [type name major]
              :as src}
             (node/fetch-node id)]
    (when-not (node/lang-exists? type name major to-lang)
      (let [author (node/author-name owner-id)
            declarations (mapv
                          (fn [{in-name :name
                                in-major :major
                                in-lang :lang}]
                            (when-not (node/lang-exists? type in-name in-major to-lang)
                              (when-let [s (node/fetch-node
                                            (node/resolve-latest-id type in-name in-major in-lang))]
                                (node/insert-node! {:id (node/uuid)
                                                    :type type
                                                    :name in-name
                                                    :lang to-lang
                                                    :major in-major
                                                    :minor 0}
                                                   (merge (select-keys s carried)
                                                          {:inputs []
                                                           :author author
                                                           :owner-id owner-id
                                                           :published-at (node/now-iso)}))
                                (node/evict-lineage! type in-name to-lang in-major)))
                            {:type type
                             :name in-name
                             :lang to-lang
                             :major in-major})
                          (:inputs src))]
        (node/insert-node! {:id (node/uuid)
                            :type type
                            :name name
                            :lang to-lang
                            :major major
                            :minor 0}
                           (merge (select-keys src carried)
                                  (into {} (remove (comp nil? val) overrides))
                                  {:inputs declarations
                                   :author author
                                   :owner-id owner-id
                                   :published-at (node/now-iso)}))
        (node/evict-lineage! type name to-lang major)))
    (fetch-by-major type name major to-lang)))

;; ---------------------------------------------------------------------------
;; Discovery / search / admin — all type-parameterised
;; ---------------------------------------------------------------------------

(defn- card
  "A discovery/search card from a row with a `content` blob — enough to render a preview
  card (identity, kind, title, prose excerpt) and its byline (author · date)."
  [row]
  (let [c (node/decode-content (:content row))]
    (assoc (select-keys row [:id :type :name :lang :major :minor])
           :kind (:kind c)
           :title (:title c)
           :text (text-of c)
           :author (:author c)
           :author-id (:owner-id c)
           :published-at (:published-at c))))

(defn search
  "Documents of `type` matching `q` in name/title/content, latest minor per lineage,
  scoped to `lang`. Blank `q` → []."
  [type q lang]
  (if (or (nil? q) (empty? q))
    []
    (let [like (str "%" q "%")]
      (mapv
       card
       (node/q!
        db/ds
        ["SELECT k.id, k.type, k.name, k.lang, k.major, k.minor, k.content FROM AGORA_DOCUMENT k
                WHERE k.type = ? AND k.lang = ?
                  AND (k.name LIKE ? OR k.content LIKE ?)
                  AND k.minor = (SELECT MAX(k2.minor) FROM AGORA_DOCUMENT k2
                                 WHERE k2.type = k.type AND k2.name = k.name
                                   AND k2.major = k.major AND k2.lang = k.lang)
                ORDER BY k.name, k.major LIMIT 50"
         type
         lang
         like
         like]
        node/kebab)))))

(defn list-latest
  "Up to 10 latest-minor documents of `type`, sampled at random, scoped to `lang`."
  [type lang]
  (mapv
   card
   (node/q!
    db/ds
    ["SELECT k.id, k.type, k.name, k.lang, k.major, k.minor, k.content FROM AGORA_DOCUMENT k
            WHERE k.type = ? AND k.lang = ?
              AND k.minor = (SELECT MAX(k2.minor) FROM AGORA_DOCUMENT k2
                             WHERE k2.type = k.type AND k2.name = k.name
                               AND k2.major = k.major AND k2.lang = k.lang)
            ORDER BY RAND() LIMIT 10"
     type
     lang]
    node/kebab)))

(defn list-recent
  "Latest-minor documents of `type` scoped to `lang`, most recent first (article
  discover). published-at lives in the content blob, so sort after decoding."
  [type lang]
  (->>
    (node/q!
     db/ds
     ["SELECT a.id, a.type, a.name, a.lang, a.major, a.minor, a.content FROM AGORA_DOCUMENT a
           WHERE a.type = ? AND a.lang = ?
             AND a.minor = (SELECT MAX(a2.minor) FROM AGORA_DOCUMENT a2
                            WHERE a2.type = a.type AND a2.name = a.name
                              AND a2.major = a.major AND a2.lang = a.lang)"
      type
      lang]
     node/kebab)
    (mapv card)
    (sort-by :published-at #(compare %2 %1))
    vec))

(defn by-author
  "The documents owned by account `author-id` (any type), latest minor per lineage,
  scoped to `lang`, as cards (most recent first) — plus `:last-activity`, the newest
  publication timestamp across them. `owner-id` lives in the content blob, so we filter
  after decoding; a `content LIKE` prefilter keeps the scan bounded before decoding."
  [author-id lang]
  (let [mine (->> (node/q!
                   db/ds
                   ["SELECT k.id, k.type, k.name, k.lang, k.major, k.minor, k.content
                       FROM AGORA_DOCUMENT k
                      WHERE k.lang = ? AND k.content LIKE ?
                        AND k.minor = (SELECT MAX(k2.minor) FROM AGORA_DOCUMENT k2
                                       WHERE k2.type = k.type AND k2.name = k.name
                                         AND k2.major = k.major AND k2.lang = k.lang)"
                    lang
                    (str "%" author-id "%")]
                   node/kebab)
                  ;; the LIKE is a cheap prefilter (the id could appear elsewhere in the
                  ;; blob); confirm on the decoded owner-id.
                  (filter #(= author-id (:owner-id (node/decode-content (:content %)))))
                  (mapv card)
                  (sort-by :published-at #(compare %2 %1))
                  vec)]
    {:kis mine
     :last-activity (->> mine (keep :published-at) sort last)}))

(defn list-tnrs
  "All lineages (name, major) of `type` with version/language/latest counts (admin)."
  [type]
  (node/q!
   db/ds
   ["SELECT name, major, COUNT(*) AS versions, COUNT(DISTINCT lang) AS langs, MAX(minor) AS latest
      FROM AGORA_DOCUMENT WHERE type = ? GROUP BY name, major ORDER BY name, major"
    type]
   node/kebab))

(defn all-tnrs
  "Every lineage of every type (KI + article + …), **one row per language version** —
  (type, name, lang, major) with its version count and latest minor. The admin table."
  []
  (node/q!
   db/ds
   ["SELECT type, name, lang, major, COUNT(*) AS versions, MAX(minor) AS latest
      FROM AGORA_DOCUMENT GROUP BY type, name, lang, major ORDER BY type, name, major, lang"]
   node/kebab))

(defn delete-tnr!
  "Drop a single language version of a lineage — (type, name, lang, major) — plus its
  successor-index rows (targeted, so no full rebuild). Returns rows removed."
  [type doc-name lang doc-major]
  (node/q!
   db/ds
   ["DELETE FROM AGORA_SUCCESSOR
      WHERE (input_type = ? AND input_name = ? AND input_lang = ? AND input_major = ?)
         OR successor_id IN (SELECT id FROM AGORA_DOCUMENT
                             WHERE type = ? AND name = ? AND lang = ? AND major = ?)"
    type
    doc-name
    lang
    doc-major
    type
    doc-name
    lang
    doc-major])
  (let [n (:next.jdbc/update-count
           (node/q1!
            db/ds
            ["DELETE FROM AGORA_DOCUMENT WHERE type = ? AND name = ? AND lang = ? AND major = ?"
             type
             doc-name
             lang
             doc-major]))]
    (node/clear-caches!)
    n))

(defn compact-tnr!
  "Keep only the latest minor of one language version — (type, name, lang, major) —
  and delete the rest. Returns rows removed."
  [type doc-name lang doc-major]
  (let
    [latest
     (:latest
      (node/q1!
       db/ds
       ["SELECT MAX(minor) AS latest FROM AGORA_DOCUMENT
                            WHERE type = ? AND name = ? AND lang = ? AND major = ?"
        type
        doc-name
        lang
        doc-major]
       node/kebab))
     n
     (or
      (:next.jdbc/update-count
       (node/q1!
        db/ds
        ["DELETE FROM AGORA_DOCUMENT
                            WHERE type = ? AND name = ? AND lang = ? AND major = ? AND minor < ?"
         type
         doc-name
         lang
         doc-major
         latest]))
      0)]
    (node/clear-caches!)
    n))

(defn sitemap-rows
  "Every public permalink of `type` as {:name :major :lang :lastmod}."
  [type]
  (->> (node/q! db/ds
                ["SELECT name, major, lang, content FROM AGORA_DOCUMENT WHERE type = ?" type]
                node/kebab)
       (map (fn [r]
              (assoc (select-keys r [:name :major :lang])
                     :published-at
                     (:published-at (node/decode-content (:content r))))))
       (group-by (juxt :name :major :lang))
       (mapv (fn [[[name major lang] rows]]
               {:name name
                :major major
                :lang lang
                ;; latest publication across this lineage's minors. ISO-8601 strings
                ;; sort lexicographically = chronologically; `max-key` can't be used
                ;; (it compares keys with `>`, which is numbers-only).
                :lastmod (->> (keep :published-at rows) sort last)}))))

(defn rebuild-successor-index!
  "Recompute AGORA_SUCCESSOR from every node's declared inputs (all types), dropping
  the in-memory caches. Delegates to the shared node layer; run daily."
  []
  (node/rebuild-successor-index!))

(defn consistency-issues
  "Scan **every version** of every node for reference problems (see the *Consistency
  rules* in agora/CLAUDE.md):

   - **`:broken`** — a **dangling reference**: an input edge / in-text `[[ki:…]]`
     citation pointing at a KI that does not exist (in any language). Inputs and
     citations are the same thing under the model, so this catches a non-existing input
     *and* a non-existing quote.
   - **`:self`** — a **self-reference**: the document cites its own lineage
     (same type + name + major). A node cannot be an input of itself — that is a
     degenerate cycle — so it is reported even though the target exists.

  Runs over current *and* former versions. Returns a vector of
  {:id :type :name :lang :major :minor :title :broken […] :self […]}, each ref as
  {:name :major :lang}; `:id` pins the exact version so the admin can deep-link to it."
  []
  (let [;; Every existing identity (any minor, any language) as [type name major] —
        ;; a referenced KI is live iff its lineage appears here (matches the engine's
        ;; cross-language fallback). Fetched ONCE and checked in memory, so the scan is
        ;; two queries total rather than one per reference (which floods the DB pool).
        existing (into
                  #{}
                  (map (juxt :type :name :major))
                  (node/q! db/ds ["SELECT DISTINCT type, name, major FROM AGORA_DOCUMENT"] node/kebab))
        ref-id (fn [r] [(or (:type r) "ki") (:name r) (:major r)])]
    (->> (node/q! db/ds
                  ["SELECT id, type, name, lang, major, minor, content FROM AGORA_DOCUMENT"]
                  node/kebab)
         (keep (fn [row]
                 (let [c (node/decode-content (:content row))
                       lang (:lang row)
                       text (text-of c)
                       self [(:type row) (:name row) (:major row)]
                       refs (distinct (concat (:inputs c) (domain/cite-refs (or text "") lang)))
                       broken (->> refs
                                   (remove (fn [r] (existing (ref-id r))))
                                   (map #(select-keys % [:name :major :lang]))
                                   distinct
                                   vec)
                       self-refs (->> refs
                                      (filter (fn [r] (= (ref-id r) self)))
                                      (map #(select-keys % [:name :major :lang]))
                                      distinct
                                      vec)]
                   (when (or (seq broken) (seq self-refs))
                     (assoc (select-keys row [:id :type :name :lang :major :minor])
                            :title (:title c)
                            :broken broken
                            :self self-refs)))))
         vec)))
