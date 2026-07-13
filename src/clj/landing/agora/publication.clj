(ns landing.agora.publication
  "Publications — the work-package that gathers a user's drafts and publishes them together. A
  publication is a `type=publication` document in AGORA_DOCUMENT, reusing the storage engine,
  with a fixed identity (major 1 / minor 0 — a publication is not versioned content) and no
  epistemic kind. Its `content` is `{:title :author :owner-id :status :published-at}`."
  (:require
   [landing.agora.db             :as db]
   [landing.agora.document       :as document]
   [landing.agora.document-store :as store]
   [landing.language             :as language]))

(defn- view
  "Endpoint view of a publication row: identity + authored fields, `:owner-id` renamed to the
  public `:author-id` (as for documents). nil for a row that is not a publication."
  [doc]
  (when (= "publication" (:type doc))
    {:id (:id doc)
     :type (:type doc)
     :title (:title doc)
     :status (:status doc)
     :author (:author doc)
     :author-id (:owner-id doc)
     :published-at (:published-at doc)}))

(defn fetch
  "The publication `id` as a view, or nil (nil too when `id` is not a publication)."
  [id]
  (some-> (store/fetch-document id)
          view))

(defn create!
  "Open a new publication owned by `owner-id`, titled `title` (content language `lang`,
  defaulting). Status starts `open`. Returns the view."
  [owner-id title lang]
  (let [id (store/uuid)]
    (store/insert-document! {:id id
                             :type "publication"
                             :name (document/unique-cid)
                             :lang (or lang language/default-lang)
                             :major 1
                             :minor 0}
                            {:title title
                             :author (store/author-name owner-id)
                             :owner-id owner-id
                             :status "open"
                             :published-at (store/now-iso)})
    (fetch id)))

(defn list-mine
  "The caller's **open** publications, newest first. Scans publications and filters by owner
  in-process — publications are few and each `fetch` is cached; a denormalized owner column
  can optimize this later if the count grows."
  [owner-id]
  (->>
    (store/q!
     db/ds
     ["SELECT id FROM AGORA_DOCUMENT
                    WHERE type = 'publication' ORDER BY published_at DESC"]
     store/kebab)
    (keep (comp fetch :id))
    (filter #(and (= owner-id (:author-id %)) (= "open" (:status %))))
    vec))
