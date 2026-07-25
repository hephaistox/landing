(ns landing.agora.document.engine
  "New Wire. Shapes a document into its endpoint view, from `cache`, the `db` layer (`db.document`,
  `db.source`) and the domain (`identity`, `kind`). Type-agnostic: every type shapes the
  same."
  (:require
   [landing.agora.db.document      :as db-doc]
   [landing.agora.db.source        :as source]
   [landing.agora.document.kind    :as dk]
   [landing.agora.document.storage :as ds]))

(defn- input-doc
  "Resolve an input `ref` to its document. A ref with an `:id` (a pinned input) loads that exact
  version. A ref without one is unresolved here (nil) — resolving by TNLR is a different path."
  [doc-storage {:keys [id]}]
  (when id (ds/fetch-id doc-storage id)))

(defn- split-inputs
  "Split resolved input refs into `{:inputs :cites}`. A `kind=source` input is a cite — an
  edge-only citation of a work. It becomes `{:name :major :id :title :author-name :author-id
  :locator}`. Others stay `:inputs`."
  [doc-storage inputs]
  (reduce (fn [acc inp]
            (let [d (input-doc doc-storage inp)]
              (if (= "source" (:kind d))
                (let [work (source/resolve-ref (:source d))]
                  (update acc
                          :cites
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
           :cites []}
          inputs))

(defn- successor-refs
  "Each successor lineage of `ref`, as `{:id latest-published-minor}`. Resolved in SQL: one id per
  lineage whose latest published minor still declares `ref`. No fetch, no domain collapse."
  [ref]
  (mapv (fn [id] {:id id}) (db-doc/successor-latest-ids ref)))

(defn- expand-document
  "Shape `doc` into the endpoint view
  — the document plus its resolved environment: inputs, `:cites`, successors and source.

  Drops `:pins` and the internal `:author`/`:owner-id`, exposing the byline person as the derived
  `:attributed-author` (name) + `:attributed-author-id` (id) — via `kind/attributed-author[-id]`: a
  source KI's cited author, else the document's owner. Name and profile link always agree.

  The version list is not part of the view — it is fetched on demand (version history, publish-time
  resolution), not on every read."
  [doc-storage doc]
  (let [{:keys [inputs cites]} (split-inputs doc-storage (:pins doc))]
    (-> doc
        (assoc :inputs inputs
               :cites cites
               :attributed-author (dk/attributed-author doc)
               :attributed-author-id (dk/attributed-author-id doc)
               :source (source/resolve-ref (:source doc))
               :successors (successor-refs doc))
        (dissoc :author :owner-id :pins))))

;; ********************************************************************************
;; Picking a document — both reads fetch a single document, then shape it. Same shape, different
;; selector: by exact id, or by the latest published minor of a lineage (DB-resolved).

(defn read-by-id
  "Document `id` as the endpoint view, or nil. One fetch, by exact id."
  [doc-storage id]
  (some->> id
           (ds/fetch-id doc-storage)
           (expand-document doc-storage)))

(defn read-by-major
  "The latest published minor of `ref` (a TNLR) as the endpoint view, or nil when the lineage has no
  published minor in that language. One fetch, DB-resolved."
  [doc-storage ref]
  (some->> ref
           (ds/fetch-latest-revision doc-storage)
           (expand-document doc-storage)))
