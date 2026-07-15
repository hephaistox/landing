(ns landing.agora.document-lineage
  "The **lineage** layer of the Agora domain — the pure operations over versions: within *a
  lineage's set of minors* (resolve which minor is current, compute the next, construct the next
  on create / edit / publish) and, at the end, over *a whole corpus of versions* spanning many
  lineages (resolve a TNLR, resolve a document's inputs, a publication's members). It sits above
  `document-identity` (TNLR + citation grammar) and `document-kind` (which kinds may take inputs),
  and it is the single home of the rule **a version's text determines its inputs**.

  Storage is an adapter *below* this: the DB (or an in-memory corpus) returns a lineage's minors
  — a plain seq of version maps — and this namespace resolves + constructs over them. So the
  SQL-backed engine (`landing.agora.document`) and the EDN-backed `corpus` call the very same
  functions; only *how the minors are fetched* differs. No I/O here — the caller supplies any
  I/O-sourced fields (author name, timestamp, a fresh id) and persists the result."
  (:require
   [landing.agora.document-identity :as di]
   [landing.agora.document-kind     :as dk]))

;; --- content & the input-derivation rule -------------------------------------

(defn inputs-of
  "The input TNLRs of a version, **derived from its content**: the `[[ki:…]]` citations in its
  text (`di/cite-refs`) plus any explicit source `:quotes` (edge-only inputs never written into
  prose), all in `lang`. Empty when the kind takes no inputs (`dk/kind-allows-inputs?`). This is
  the one rule 'new text → new inputs', so every caller derives inputs the same way."
  [content lang]
  (if (dk/kind-allows-inputs? (:kind content))
    (->> (concat (di/cite-refs (:text content) lang)
                 (map (fn [{:keys [name major]}]
                        {:type "ki"
                         :name name
                         :lang lang
                         :major major})
                      (:quotes content)))
         (distinct)
         (vec))
    []))

;; --- resolution over a lineage's minors --------------------------------------
;; `minors` is a seq of versions that all share one TNLR (the caller has already narrowed to a
;; single lineage — the DB with a WHERE clause, the corpus by scanning its vector).

(defn latest
  "The current version among a lineage's `minors` — the highest minor, **drafts included** (the
  owner-facing / working view). nil for an empty lineage."
  [minors]
  (when (seq minors) (apply max-key :minor minors)))

(defn latest-published
  "The highest **published** (non-draft) minor among `minors` — the public view. nil for a
  draft-only lineage."
  [minors]
  (let [published (remove :draft minors)] (when (seq published) (apply max-key :minor published))))

(defn next-minor
  "The minor a new version would take: one past the highest in `minors`, or 0 if empty."
  [minors]
  (if (seq minors) (inc (:minor (latest minors))) 0))

;; --- lifecycle constructors (pure) -------------------------------------------
;; Each returns a new version *value*. The text drives the inputs; the caller adds any
;; I/O-sourced fields (author, timestamp, id) and persists.

(defn create
  "A brand-new version (major 1, minor 0, draft) from `content` — its identity (`:type :name
  :lang`) and authored fields (`:kind :title :text …`) — with `:inputs` derived from its text."
  [content]
  (-> content
      (merge {:major 1
              :minor 0
              :draft true})
      (assoc :inputs (inputs-of content (:lang content)))))

(defn edit
  "A new **minor** of the lineage whose versions are `minors`: carry the current version forward,
  apply `changes`, **re-derive `:inputs` from the (possibly edited) text**, and mark it draft. nil
  if the lineage is empty. Per-version fields (`:id`, `:pins`) are dropped — the caller reassigns
  them on persist."
  [minors changes]
  (when-let [current (latest minors)]
    (let [merged (-> (merge current changes)
                     (dissoc :id :pins))]
      (assoc merged
             :minor (next-minor minors)
             :draft true
             :inputs (inputs-of merged (:lang merged))))))

(defn publish
  "**Close=publish** a publication: `publication` becomes closed + published, and **every draft it
  gathers** (`members` — the versions linked to it) becomes published (`:draft false`), **all at
  once**. Publishing simultaneously keeps the invariant that no published version depends on a draft,
  so the flip is order-independent. `:status` and `:draft` stay consistent on the publication
  (closed ⟺ published). Returns `[publication & members]`, all published; the caller persists (and,
  in the DB, prunes each lineage's intermediate drafts). Finding a publication's members is a
  collection query the caller runs first (the corpus by `:publication`, the DB by `publication_id`)."
  [publication members]
  (into [(assoc publication :status "closed" :draft false)] (map #(assoc % :draft false)) members))

;; --- graph over a corpus of versions -----------------------------------------
;; Everything above operates on ONE lineage's minors. These operate on a whole `corpus` — a
;; collection of versions spanning many lineages (the EDN vector, or a DB result set) passed as
;; plain data. They are the rules the in-memory corpus runs directly; the SQL engine implements
;; the same rules as indexed queries.

(defn resolve-latest
  "The current version of lineage `tnlr` within `corpus` (a collection spanning many lineages) —
  narrow to the lineage, then `latest`. nil if absent."
  [corpus tnlr]
  (latest (filter #(di/same-tnlr? % tnlr) corpus)))

(defn declared-inputs
  "A version's declared inputs — its `:inputs` (derived from the text at create/edit time and
  stored on the version). Empty for a leaf or a publication."
  [doc]
  (vec (:inputs doc)))

(defn resolved-inputs
  "Each declared input of `doc`, resolved against `corpus`: `[{:tnlr … :doc <current|nil>} …]`. A
  nil `:doc` is a dangling reference (no such lineage in this language)."
  [corpus doc]
  (->> doc
       declared-inputs
       (mapv (fn [t]
               {:tnlr t
                :doc (resolve-latest corpus t)}))))

(defn publication-of
  "The publication `doc` belongs to — its `:publication` TNLR resolved in `corpus`, or nil (a
  document created outside any publication)."
  [corpus doc]
  (some->> (:publication doc)
           (resolve-latest corpus)))

(defn members
  "The documents belonging to publication `pub` — every lineage whose `:publication` link points
  at `pub`, each at its current version, sorted. The publication's modified set, keyed by the
  provenance edge (not the reasoning graph)."
  [corpus pub]
  (->> corpus
       (filter #(some-> (:publication %)
                        (di/same-tnlr? pub)))
       (map #(resolve-latest corpus (di/tnlr %)))
       (distinct)
       (sort-by (juxt :type :name))))

(defn latest-lineages
  "The current version of every distinct lineage in `corpus`, sorted for a stable listing."
  [corpus]
  (->> corpus
       (map di/tnlr)
       (distinct)
       (map #(resolve-latest corpus %))
       (sort-by (juxt :type :name :lang))))
