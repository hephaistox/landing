(ns landing.agora.corpus
  "The in-memory **corpus** substrate — a corpus is a flat vector of document versions, and this
  namespace owns only what is *specific to that substrate*: the **peek** (narrow the vector to one
  lineage's minors, the analog of the DB's `WHERE` clause) and the two **append** mutations
  (create / edit add a new version). Nothing else — resolution, the graph queries and the compact
  printing are domain logic in `landing.agora.document-lineage` / `landing.agora.corpus-print`, so
  `corpus` and the SQL-backed `landing.agora.document` engine differ *only* in how a lineage's
  versions are fetched and stored.

  So the whole corpus API is `versions` / `create` / `edit`; a caller resolves with
  `(document-lineage/… (corpus/versions corpus tnlr))` or `(document-lineage/… corpus …)` over the
  vector directly."
  (:require
   [landing.agora.document-identity :as di]
   [landing.agora.document-lineage  :as lineage]))

(defn versions
  "The minors of lineage `tnlr` in `corpus` — the corpus's *peek*. (The DB does this with a
  `WHERE type/name/lang/major` clause; here we scan the vector.) The result is what every
  `document-lineage` lineage resolver expects: a seq of one lineage's versions."
  [corpus tnlr]
  (filterv #(di/same-tnlr? % tnlr) corpus))

(defn create
  "Add a brand-new document built from `doc` (its `:inputs` derived from its text by
  `document-lineage/create`) to `corpus`. Returns `[corpus' new-version]`."
  [corpus doc]
  (let [v (lineage/create doc)] [(conj (vec corpus) v) v]))

(defn edit
  "Edit lineage `tnlr` → a new minor (`document-lineage/edit` re-derives its inputs from the edited
  text). Returns `[corpus' new-version]`, or `[corpus nil]` if the lineage is absent."
  [corpus tnlr changes]
  (if-let [v (lineage/edit (versions corpus tnlr) changes)]
    [(conj (vec corpus) v) v]
    [corpus nil]))
