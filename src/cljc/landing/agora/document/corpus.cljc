(ns landing.agora.document.corpus
  "New Wire. A portable (cljc) in-memory **corpus** holder — a corpus is a flat vector of document versions,
  usable from any stack (the CLI today; a client-side graph feature could hold one too). This
  namespace owns what is *specific to that substrate*:

  - the **peek** (narrow the vector to one lineage's minors, the analog of the DB's `WHERE` clause),
  - the two **append** mutations (create / edit) and
  - the **whole-corpus graph queries** (`resolve-latest`, `resolved-inputs`, `publication-of`, `members`,
  `latest-lineages`) — each a linear scan of the vector composed with a pure `landing.agora.document.lineage`
  rule.

  The scanning is the holder's concern, so it lives here rather than in the shared domain: the SQL-backed
  `landing.agora.document-old` engine narrows the same rules with indexed queries instead. Only *how versions are fetched* differs."
  (:require
   [landing.agora.document.identity :as di]
   [landing.agora.document.lineage  :as lineage]))

(defn versions
  "The minors of lineage `tnlr` in `corpus` — the corpus's *peek*. (The DB does this with a
  `WHERE type/name/lang/major` clause; here we scan the vector.) The result is what every
  `document.lineage` lineage resolver expects: a seq of one lineage's versions."
  [corpus tnlr]
  (filterv #(di/same-tnlr? % tnlr) corpus))

(defn create
  "Add a brand-new document built from `doc` (its `:inputs` derived from its text by
  `document.lineage/create`) to `corpus`. Returns `[corpus' new-version]`."
  [corpus doc]
  (let [v (lineage/create doc)] [(conj (vec corpus) v) v]))

(defn edit
  "Edit lineage `tnlr` → a new minor (`document.lineage/edit` re-derives its inputs from the edited
  text). Returns `[corpus' new-version]`, or `[corpus nil]` if the lineage is absent."
  [corpus tnlr changes]
  (if-let [v (lineage/edit (versions corpus tnlr) changes)]
    [(conj (vec corpus) v) v]
    [corpus nil]))

;; --- graph queries over the whole corpus -------------------------------------
;; Each is the corpus's *peek* (`versions`, a linear scan) composed with a pure `lineage` rule.
;; A different holder (the SQL engine, a frontend cache) narrows its own way and applies the same
;; pure rules — so these whole-corpus scans stay here, out of the shared domain.

(defn- latest-with-drafts
  "The highest minor of a lineage's `minors`, drafts included — the holder's own copy (the domain
  keeps this private, as it is an owner/working-view resolver)."
  [minors]
  (when (seq minors) (apply max-key :minor minors)))

(defn resolve-latest
  "The current version (latest minor, drafts included) of lineage `tnlr` in `corpus`, or nil."
  [corpus tnlr]
  (latest-with-drafts (versions corpus tnlr)))

(defn resolved-inputs
  "Each declared input of `doc`, resolved against `corpus`: `[{:tnlr … :doc <current|nil>} …]`.
  A nil `:doc` is a dangling reference (no such lineage in this language)."
  [corpus doc]
  (mapv (fn [t]
          {:tnlr t
           :doc (resolve-latest corpus t)})
        (lineage/declared-inputs doc)))

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
