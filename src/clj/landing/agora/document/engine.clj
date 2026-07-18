(ns landing.agora.document.engine
  "New Wire. Shapes a document into its endpoint view, from `cache`, the `db` layer (`db.document`,
  `db.source`) and the domain (`identity`, `lineage`). Type-agnostic: every type shapes the same."
  (:require
   [landing.agora.db.document       :as db]
   [landing.agora.db.source         :as source]
   [landing.agora.document.cache    :as cache]
   [landing.agora.document.identity :as di]
   [landing.agora.document.lineage  :as lineage]))

(defn- input-doc
  "Resolve an input `ref` to its document. A ref with an `:id` (a pinned input) loads that exact
  version. A ref without one is unresolved here (nil) — resolving by TNLR is a different path."
  [{:keys [id]}]
  (when id (cache/document id)))

(defn- split-inputs
  "Split resolved input refs into `{:inputs :quotes}`. A `kind=source` input is a quote — an
  edge-only citation of a work. It becomes `{:name :major :id :title :author-name :author-id
  :locator}`. Others stay `:inputs`."
  [inputs]
  (reduce (fn [acc inp]
            (let [d (input-doc inp)]
              (if (= "source" (:kind d))
                (let [work (source/resolve-ref (:source d))]
                  (update acc
                          :quotes
                          conj
                          {:name (:name inp)
                           :major (:major inp)
                           :id (:id inp)
                           :title (:title d)
                           :author-name (:author-name work)
                           :author-id (:author-id work)
                           :locator (:locator work)}))
                (update acc :inputs conj inp))))
          {:inputs []
           :quotes []}
          inputs))

(defn- successor-refs
  "Distinct successor lineages of `ref`, each `{:id latest-published-minor}`. Drops dangling ids and
  drafts, then collapses each lineage's published minors to its latest. A draft-only successor is
  dropped whole (removed before grouping), so it never resolves to a nil id."
  [ref]
  (->> (db/successor-ids ref)
       (keep cache/document)
       (remove :draft) ; an unpublished successor stays hidden until it is published
       (group-by (juxt :type :name :lang :major))
       vals
       (mapv (fn [ds] {:id (:id (lineage/latest-published ds))}))))

(defn view-full
  "The **full** endpoint view of `doc` — the document plus its whole environment: resolved inputs,
  `:quotes`, successors, versions, translations, source. Several queries; for a document page or
  edition, not a list. Drops `:pins`. Renames `:owner-id` to `:author-id`. Attributes the statement
  to the first quote's author."
  [doc]
  (let [{:keys [inputs quotes]} (split-inputs (di/input-refs (:inputs doc) (:pins doc)))]
    (-> doc
        (assoc :inputs inputs
               :quotes quotes
               :quote-author-name (:author-name (first quotes))
               :author-id (:owner-id doc)
               :source (source/resolve-ref (:source doc))
               :successors (successor-refs doc)
               :versions (db/versions doc)
               :translations (db/translations doc))
        (dissoc :owner-id :pins))))

(defn read-by-id
  "Document `id` as the endpoint view, or nil. Loads by exact id."
  [id]
  (when-let [doc (cache/document id)] (view-full doc)))
