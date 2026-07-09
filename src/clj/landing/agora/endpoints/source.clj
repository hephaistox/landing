(ns landing.agora.endpoints.source
  "Bibliographic source API over AGORA_SOURCE (see landing.agora.source): search by
  author/title/year, create, and list-recent (one-click reuse). Search is anonymous;
  create + recent need a logged-in author."
  (:require
   [landing.agora.endpoints.error     :as error]
   [landing.agora.endpoints.throttle  :as throttle]
   [landing.agora.source              :as source]
   [muuntaja.core                     :as m]
   [reitit.coercion.malli             :refer [coercion]]
   [reitit.ring.coercion              :as rcoercion]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(def ^:private throttled [(:middleware-fn throttle/authoring-rate-limiter)])
(defn- uid [req] (get-in req [:session :user-id]))
(def ^:private unauthorized
  {:status 401
   :body {:error "login required"}})

(def ^:private search-handler
  (fn [req]
    {:status 200
     :body (source/search (get-in req [:parameters :query]))}))

(def ^:private recent-handler
  (fn [req]
    (if-let [u (uid req)]
      {:status 200
       :body (source/list-recent u)}
      unauthorized)))

(def ^:private create-handler
  (fn [req]
    (if-let [u (uid req)]
      {:status 201
       :body (source/create! u (get-in req [:parameters :body]))}
      unauthorized)))

(def ^:private edit-handler
  (fn [req]
    (if (uid req)
      (if-let [s (source/update! (get-in req [:parameters :path :id])
                                 (get-in req [:parameters :body]))]
        {:status 200
         :body s}
        {:status 404
         :body {:error "source not found"}})
      unauthorized)))

(def ^:private create-body
  [:map
   [:person-id
    [:string {:min 1
              :max 64}]]
   [:title
    [:string {:min 1
              :max 512}]]
   [:year {:optional true}
    [:maybe :int]]
   [:editor {:optional true}
    [:maybe [:string {:max 255}]]]])

(def ^:private search-query
  [:map
   [:author {:optional true}
    [:maybe [:string {:max 200}]]]
   [:title {:optional true}
    [:maybe [:string {:max 200}]]]
   [:year {:optional true}
    [:maybe :int]]])

(defn source-routes
  [prefix]
  [prefix {:coercion coercion
           :muuntaja m/instance
           :swagger {:tags #{:agora}}
           :conflicting true
           :middleware [parameters/parameters-middleware
                        muuntaja/format-negotiate-middleware
                        muuntaja/format-response-middleware
                        error/exception-middleware
                        muuntaja/format-request-middleware
                        rcoercion/coerce-request-middleware]}
   [""
    {:get {:handler search-handler
           :operationId "agora-source-search"
           :parameters {:query search-query}
           :summary "Search sources by author / title / year"}
     :post {:handler create-handler
            :middleware throttled
            :operationId "agora-source-create"
            :parameters {:body create-body}
            :summary "Create a bibliographic source"}}]
   ["/recent"
    {:get {:handler recent-handler
           :operationId "agora-source-recent"
           :summary "Recently created sources (one-click reuse)"}}]
   ["/:id"
    {:post {:handler edit-handler
            :middleware throttled
            :operationId "agora-source-edit"
            :parameters {:path [:map [:id :string]]
                         :body create-body}
            :summary "Edit an existing source"}}]])
