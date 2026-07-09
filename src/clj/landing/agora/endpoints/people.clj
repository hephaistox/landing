(ns landing.agora.endpoints.people
  "People directory API — search AGORA_USER by name (for the source author picker) and
  create login-less **external** people (cited authors with no account). Search is
  anonymous (public directory data); creating a person requires a logged-in author."
  (:require
   [landing.agora.auth                :as auth]
   [landing.agora.endpoints.error     :as error]
   [landing.agora.endpoints.throttle  :as throttle]
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
     :body (auth/search-people (get-in req [:parameters :query :q]))}))

(def ^:private create-handler
  (fn [req]
    (if (uid req)
      {:status 201
       :body (auth/create-external-person! (get-in req [:parameters :body :display-name]))}
      unauthorized)))

(defn people-routes
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
           :operationId "agora-people-search"
           :parameters {:query [:map
                                [:q {:optional true}
                                 [:maybe [:string {:max 200}]]]]}
           :summary "Search people (accounts + external cited authors) by name"}
     :post {:handler create-handler
            :middleware throttled
            :operationId "agora-people-create"
            :parameters {:body [:map
                                [:display-name
                                 [:string {:min 1
                                           :max 255}]]]}
            :summary "Create an external (login-less) cited person"}}]])
