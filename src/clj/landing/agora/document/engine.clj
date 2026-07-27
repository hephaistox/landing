(ns landing.agora.document.engine
  "Shapes a document into its endpoint view, from the `db` layer (`db.document`) and the domain
  (`identity`, `kind`). Type-agnostic: every type shapes the same."
  (:require
   [clojure.string                 :as str]
   [landing.agora.db.document      :as db-doc]
   [landing.agora.document.kind    :as dk]
   [landing.agora.document.storage :as ds]))

(defn- resolve-source
  "Resolve a `:source` ref `{:source-id :locator}` to the cited source's display fields + locator,
  fetching the source document through `doc-storage` (cached). nil for a blank/absent ref or an
  unknown source."
  [doc-storage {:keys [source-id locator]}]
  (when-not (str/blank? source-id)
    (when-let [d (ds/fetch-id doc-storage source-id)]
      (-> (select-keys d [:author-id :author-name :title :year :editor :url :owner-id])
          (assoc :source-id source-id :locator locator)))))

(defn- split-inputs
  "Split resolved input refs into `{:inputs :cites}`. A `kind=source` input is a cite — an
  edge-only citation of a source. It becomes `{:name :major :id :title :author-name :author-id
  :locator}`. Others stay `:inputs`."
  [doc-storage inputs]
  (reduce (fn [acc
               {:keys [id]
                :as inp}]
            (when id
              (let [d (ds/fetch-id doc-storage id)]
                (if (= :source (:kind d))
                  (let [src (resolve-source doc-storage (:source d))]
                    (update acc
                            :cites
                            conj
                            {:name (:name inp)
                             :major (:major inp)
                             :id (:id inp)
                             :title (:title d)
                             :author-name (:author-name src)
                             :author-id (:author-id src)
                             :locator (:locator src)}))
                  (update acc :inputs conj inp)))))
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
               :source (resolve-source doc-storage (:source doc))
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

(defn- card
  "A browse card for `doc`: identity and kind, title, prose, the byline, and the resolved source —
  enough to render a preview without the full input/successor environment."
  [doc-storage doc]
  (-> doc
      (select-keys [:id :type :name :lang :major :minor :draft :kind :title :text :published-at])
      (assoc :attributed-author (dk/attributed-author doc)
             :attributed-author-id (dk/attributed-author-id doc)
             :source (resolve-source doc-storage (:source doc)))))

(defn list-cards
  "One page of browse cards for `type` in `lang`, newest first (`limit`/`offset`). The page of
  documents comes from `doc-storage` (cached); each is shaped into a card."
  [doc-storage type lang limit offset]
  (mapv #(card doc-storage %) (ds/documents doc-storage type lang limit offset)))

(defn sitemap-rows
  "Every published lineage's permalink row for the sitemap: `{:type :name :major :lang :title
  :lastmod}`, projected from `doc-storage`'s published-latest set."
  [doc-storage]
  (mapv #(select-keys % [:type :name :major :lang :title :lastmod])
        (ds/published-latest doc-storage)))

(defn author-documents
  "The author hub's document list: every published document whose derived byline person
  (`kind/attributed-author-id` — a source's cited author, else the owner) is `author-id`, as
  permalink link items. The source is resolved per document so a `kind=source` citation lands under
  its cited author, consistent with its byline."
  [doc-storage author-id]
  (into []
        (comp (map (fn [d] (assoc d :source (resolve-source doc-storage (:source d)))))
              (filter (fn [d] (= author-id (dk/attributed-author-id d))))
              (map #(select-keys % [:type :name :lang :major :title :lastmod])))
        (ds/published-latest doc-storage)))

(defn- link-of
  "A document's permalink link item `{:type :name :lang :major :title}` — `:lang` as a string, the
  form a server-rendered link builds its URL from."
  [doc]
  (-> (select-keys doc [:type :name :lang :major :title])
      (update :lang name)))

(defn resolve-links
  "Resolve neighbour refs (`{:id …}`) to permalink link items `{:type :name :lang :major :title}`,
  fetching each through `doc-storage` (cached). Skips a ref whose document is absent. Used to give a
  server-rendered body real, crawlable links instead of bare ids."
  [doc-storage refs]
  (into []
        (keep (fn [{:keys [id]}]
                (some->> id
                         (ds/fetch-id doc-storage)
                         link-of)))
        refs))
