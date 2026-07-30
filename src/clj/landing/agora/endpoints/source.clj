(ns landing.agora.endpoints.source
  "Bibliographic sources for citation: search existing source works by author / title / year, and
  list recent ones for the picker. A source is a `type=source` document, so creating or editing one
  is a document write — not wired here yet (`todo`)."
  (:require
   [clojure.string                    :as str]
   [landing.agora.db.document         :as db-doc]
   [muuntaja.core                     :as m]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(def ^:private mw
  [parameters/parameters-middleware
   muuntaja/format-negotiate-middleware
   muuntaja/format-response-middleware
   muuntaja/format-request-middleware])

(defn- source-view
  "A source document reduced to the picker's display fields (the author is denormalized in content)."
  [doc]
  (select-keys doc [:id :title :year :editor :url :author-name :author-id]))

(defn- all-sources [] (mapv source-view (db-doc/latest-published-of-type :source)))

(defn- lc [s] (str/lower-case (str s)))

(defn- match?
  "A source matches when every provided filter matches — author/title as a substring, year exactly."
  [author title year s]
  (and (or (str/blank? author) (str/includes? (lc (:author-name s)) (lc author)))
       (or (str/blank? title) (str/includes? (lc (:title s)) (lc title)))
       (or (str/blank? year) (= (str (:year s)) (str/trim year)))))

(defn- search
  "Sources matching the `author`/`title`/`year` query filters (all blank → none), capped at 30."
  [req]
  (let [{:strs [author title year]} (:query-params req)]
    {:status 200
     :body (if (every? str/blank? [author title year])
             []
             (into [] (comp (filter #(match? author title year %)) (take 30)) (all-sources)))}))

(defn- recent
  "The most recently published sources, for the picker's suggestions."
  [_req]
  {:status 200
   :body (vec (take 10 (all-sources)))})

(defn- todo
  "Creating/editing a source is a document write, not wired here yet."
  [_req]
  {:status 501
   :body {:error "not implemented"}})

(defn source-routes
  [prefix]
  [prefix {:muuntaja m/instance
           :middleware mw}
   [""
    {:get {:handler search
           :operationId "agora-source-search"
           :summary "Search source works by author / title / year"}
     :post {:handler todo
            :operationId "agora-source-create"
            :summary "Create a source work"}}]
   ["/recent"
    {:get {:handler recent
           :operationId "agora-source-recent"
           :summary "Recently published sources"}}]])
