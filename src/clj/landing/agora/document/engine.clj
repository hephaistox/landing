(ns landing.agora.document.engine
  "Shapes a document into its endpoint view, from the `db` layer (`db.document`) and the domain
  (`identity`, `kind`). Type-agnostic: every type shapes the same."
  (:require
   [clojure.string                  :as str]
   [landing.agora.db.document       :as db-doc]
   [landing.agora.document.identity :as di]
   [landing.agora.document.kind     :as dk]
   [landing.agora.document.storage  :as ds]
   [landing.agora.publication       :as publication]
   [landing.agora.source            :as source]))

(defn- resolve-source
  "Resolve a `:source` ref `{:source-id :locator}` to the cited work's display fields + locator.
  Sources live in the dedicated `AGORA_SOURCE` table (see `landing.agora.source`), not the document
  table — so this delegates there rather than fetching a document. Carries both the work's author and
  the Agora contributor. nil for a blank/absent ref or an unknown source."
  [_doc-storage ref]
  (source/resolve-ref ref))

(defn- resolve-publication
  "Resolve a document's `publication-id` (a cid) to `{:id :title :status}` — the work-package it
  belongs to, so the reader can open it. Reads through `publication/fetch` (cached). nil when there
  is none."
  [pub-cid]
  (some-> pub-cid
          publication/fetch
          (select-keys [:id :title :status])))

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

  `:translations` (the concept's language siblings) is included so the language switcher can offer
  the other languages."
  [doc-storage doc]
  (let [{:keys [inputs cites]} (split-inputs doc-storage (:pins doc))]
    (-> doc
        (assoc :inputs inputs
               :cites cites
               :attributed-author (dk/attributed-author doc)
               :attributed-author-id (dk/attributed-author-id doc)
               :source (resolve-source doc-storage (:source doc))
               :publication (resolve-publication (:publication-id doc))
               :successors (successor-refs doc)
               :translations (db-doc/translations-of (:name doc)))
        (dissoc :author :owner-id :pins :publication-id))))

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

(defn- cite-titles
  "Each KI cited inline in `doc`'s text, as `{:name cid :title current-title}`, resolved through
  `doc-storage` (cached). Lets a card excerpt show the cited titles in place of the raw `[[ki:…]]`
  tokens. Skips a citation whose lineage no longer resolves."
  [doc-storage doc]
  (into []
        (keep (fn [ref]
                (let [ref (update ref :lang #(or % (:lang doc)))]
                  (when-let [d (ds/fetch-latest-revision doc-storage ref)]
                    {:name (:name ref)
                     :title (:title d)}))))
        (di/cite-refs (:text doc))))

(defn- card
  "A browse card for `doc`: identity and kind, title, prose, the byline, the resolved source, and the
  titles of the KIs it cites (`:cite-titles`) — enough to render a preview with a readable excerpt,
  without the full input/successor environment."
  [doc-storage doc]
  (-> doc
      (select-keys [:id :type :name :lang :major :minor :draft :kind :title :text :published-at])
      (assoc :attributed-author (dk/attributed-author doc)
             :attributed-author-id (dk/attributed-author-id doc)
             :source (resolve-source doc-storage (:source doc))
             :cite-titles (cite-titles doc-storage doc))))

(defn- lineage-key
  "The lineage a document belongs to — its identity minus minor. The overlay key: a publication draft
  and the published version it supersedes share it."
  [d]
  [(:type d) (:name d) (:lang d) (:major d)])

(defn- publication-drafts
  "The drafts publication `pub-cid` gathers for `type` in `lang` (newest first) — the in-progress
  overlay a scoped view lays over the published corpus. `type`/`lang` are the request's strings."
  [pub-cid type lang]
  (filterv (fn [d] (and (= type (name (:type d))) (= lang (name (:lang d)))))
           (db-doc/in-publication pub-cid)))

(defn- matches?
  "True when `q` (case-insensitive) occurs in a document's name, title or text — the same reach as the
  published search, applied to a publication's drafts."
  [q d]
  (str/includes? (str/lower-case (str (:name d) " " (:title d) " " (:text d))) (str/lower-case q)))

(defn list-cards
  "One page of browse cards for `type` in `lang`, newest first (`limit`/`offset`). The page of
  documents comes from `doc-storage` (cached); each is shaped into a card. When `pub-cid` is given
  (the caller's active publication), its drafts of this type/lang lead the first page and replace the
  published version of any lineage they supersede — the corpus as it will read once the publication
  is published."
  [doc-storage type lang limit offset pub-cid]
  (let [drafts (when pub-cid (publication-drafts pub-cid type lang))
        overridden (set (map lineage-key drafts))
        published (remove #(overridden (lineage-key %))
                          (ds/documents doc-storage type lang limit offset))
        head (if (zero? offset) drafts [])]
    (mapv #(card doc-storage %) (concat head published))))

(defn search-cards
  "Browse cards for documents of `type` in `lang` matching `q` (name or content). A blank `q` returns
  no results. Not cached — the query runs per keystroke against the DB. When `pub-cid` is given (the
  caller's active publication), its matching drafts lead and replace the published version of any
  lineage they supersede."
  [doc-storage type lang q pub-cid]
  (if (str/blank? q)
    []
    (let [drafts (when pub-cid (filterv #(matches? q %) (publication-drafts pub-cid type lang)))
          overridden (set (map lineage-key drafts))
          published (remove #(overridden (lineage-key %)) (db-doc/search-of-type type lang q 50))]
      (mapv #(card doc-storage %) (concat drafts published)))))

(defn publication-cards
  "Browse cards for the documents a publication gathers — the drafts whose `publication_id` is
  `pub-cid` — newest first."
  [doc-storage pub-cid]
  (mapv #(card doc-storage %) (db-doc/in-publication pub-cid)))

(defn sitemap-rows
  "Every published lineage's permalink row for the sitemap: `{:type :name :major :lang :title
  :lastmod}`, projected from `doc-storage`'s published-latest set."
  [doc-storage]
  (mapv #(select-keys % [:type :name :major :lang :title :lastmod])
        (ds/published-latest doc-storage)))

(def ^:private author-docs-limit
  "Cap on how many documents an author page lists (newest first). A holding value until the page
  paginates."
  50)

(defn- author-refs
  "The published-latest entries whose derived byline person (`kind/attributed-author-id` — a source's
  cited author, else the owner) is `author-id`, newest first, capped at `author-docs-limit`. The
  source is resolved per document so a `kind=source` citation matches its cited author, consistent
  with its byline."
  [doc-storage author-id]
  (into []
        (comp (map (fn [d] (assoc d :source (resolve-source doc-storage (:source d)))))
              (filter (fn [d] (= author-id (dk/attributed-author-id d))))
              (take author-docs-limit))
        (ds/published-latest doc-storage)))

(defn author-documents
  "Permalink link items of the documents attributed to `author-id` — the server-rendered author hub's
  list."
  [doc-storage author-id]
  (mapv #(select-keys % [:type :name :lang :major :title :lastmod])
        (author-refs doc-storage author-id)))

(defn author-cards
  "Full browse cards for the documents attributed to `author-id` — the interactive author page's grid.
  Each attributed lineage is fetched (cached) and shaped like a discover card."
  [doc-storage author-id]
  (mapv (fn [d]
          (card doc-storage
                (ds/fetch-latest-revision doc-storage
                                          {:type (keyword (:type d))
                                           :name (:name d)
                                           :lang (keyword (:lang d))
                                           :major (:major d)})))
        (author-refs doc-storage author-id)))

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
