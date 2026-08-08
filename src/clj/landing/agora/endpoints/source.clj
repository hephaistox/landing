(ns landing.agora.endpoints.source
  "Bibliographic sources for citation: search existing source works by author / title / year, and
  list recent ones for the picker. Sources live in the `AGORA_SOURCE` table (`landing.agora.source`);
  creating or editing one is not wired here yet (`todo`)."
  (:require
   [landing.agora.source              :as source]
   [muuntaja.core                     :as m]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(def ^:private mw
  [parameters/parameters-middleware
   muuntaja/format-negotiate-middleware
   muuntaja/format-response-middleware
   muuntaja/format-request-middleware])

(defn- search
  "Sources matching the `author`/`title`/`year` query filters (all blank → none)."
  [req]
  (let [{:strs [author title year]} (:query-params req)]
    {:status 200
     :body (source/search {:author author
                           :title title
                           :year year})}))

(defn- recent
  "The most recently added sources, for the picker's suggestions."
  [_req]
  {:status 200
   :body (source/recent)})

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
