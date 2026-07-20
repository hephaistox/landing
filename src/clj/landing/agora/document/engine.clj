(ns landing.agora.document.engine
  "New Wire. Shapes a document into its endpoint view, from `cache`, the `db` layer (`db.document`,
  `db.source`) and the domain (`identity`, `lineage`). Type-agnostic: every type shapes the same."
  (:require
   [landing.agora.db.document       :as db]
   [landing.agora.db.source         :as source]
   [landing.agora.document.cache    :as cache]
   [landing.agora.document.identity :as di]
   [landing.agora.document.lineage  :as lineage]
   [landing.language                :as language]))

(defn- input-doc
  "Resolve an input `ref` to its document. A ref with an `:id` (a pinned input) loads that exact
  version. A ref without one is unresolved here (nil) — resolving by TNLR is a different path."
  [{:keys [id]}]
  (when id (cache/document id)))

(defn- split-inputs
  "Split resolved input refs into `{:inputs :cites}`. A `kind=source` input is a cite — an
  edge-only citation of a work. It becomes `{:name :major :id :title :author-name :author-id
  :locator}`. Others stay `:inputs`."
  [inputs]
  (reduce (fn [acc inp]
            (let [d (input-doc inp)]
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

(defn expand-document
  "Shape `doc` into the full endpoint view — the document plus its whole environment: resolved
  inputs, `:cites`, successors, source, plus the given `versions` and `translations`. The caller
  supplies those two (queried fresh, or drawn from an already-fetched sibling set), since they are
  what a by-id and a by-major read hold differently. Drops `:pins`. Renames `:owner-id` to
  `:author-id`. Attributes the statement to the first cite's author."
  [doc versions translations]
  (let [{:keys [inputs cites]} (split-inputs (di/input-refs (:inputs doc) (:pins doc)))]
    (-> doc
        (assoc :inputs inputs
               :cites cites
               :cite-author-name (:author-name (first cites))
               :author-id (:owner-id doc)
               :source (source/resolve-ref (:source doc))
               :successors (successor-refs doc)
               :versions versions
               :translations translations)
        (dissoc :owner-id :pins))))

(defn view-full
  "The full endpoint view of `doc` — a by-id read, so its versions (drafts included) and translations
  are queried fresh. Several queries; for a document page or edition, not a list."
  [doc]
  (expand-document doc (db/versions doc) (db/translations doc)))

(defn read-by-id
  "Document `id` as the endpoint view, or nil. Loads by exact id."
  [id]
  (when-let [doc (cache/document id)] (view-full doc)))

(defn- versions-of
  "The `:versions` list — this lineage's published minors — drawn from the TNR's published docs
  `pubs` (no extra query). Public, so drafts are absent by construction."
  [pubs doc]
  (->> pubs
       (filter #(= (:lang doc) (:lang %)))
       (sort-by :minor)
       (mapv #(select-keys % [:id :minor :draft :publication-id]))))

(defn- translations-of
  "The `:translations` list — other-language published siblings `{:name :major :lang}` — drawn from
  the TNR's docs `pubs` (no extra query). Same set/shape as `db/translations`."
  [pubs doc]
  (->> pubs
       (remove #(= (:lang doc) (:lang %)))
       (map #(select-keys % [:name :major :lang]))
       distinct
       (sort-by :lang)
       vec))

(defn read-by-major
  "The published document of TNR `ref` (type, name, major) to serve for the reader's `lang`, as the
  endpoint view — the public permalink — or nil. **One** query fetches the TNR's published docs
  (every language, every minor); `lineage/resolve-latest` picks the latest in `lang` (cross-language
  fallback to the default lang); the version + translation lists are drawn from that same set, no
  re-query. Drafts never resolve here — they surface by exact id inside a change."
  [{:keys [lang]
    :as ref}]
  (let [pubs (db/published-of-tnr ref)]
    (when-let [doc (lineage/resolve-latest pubs lang language/default-language)]
      (expand-document doc (versions-of pubs doc) (translations-of pubs doc)))))
