(ns landing.agora.endpoints.publication
  "Publication API: open a publication, fetch one by id, and list the caller's open
  publications. A publication is owned by its creator, so create and list are auth-gated;
  fetch-by-id is public (a publication is not secret, just a work-package)."
  (:require
   [landing.agora.endpoints.error     :as error]
   [landing.agora.endpoints.throttle  :as throttle]
   [landing.agora.publication         :as publication]
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

(def ^:private mine-handler
  (fn [req]
    (if-let [u (uid req)]
      {:status 200
       :body (publication/list-mine u)}
      unauthorized)))

(def ^:private create-handler
  (fn [req]
    (if-let [u (uid req)]
      {:status 201
       :body (publication/create! u
                                  (get-in req [:parameters :body :title])
                                  (get-in req [:parameters :body :lang]))}
      unauthorized)))

(def ^:private fetch-handler
  (fn [req]
    (if-let [d (publication/fetch (get-in req [:parameters :path :id]))]
      {:status 200
       :body d}
      {:status 404
       :body {:error "not found"}})))

(defn publication-routes
  [prefix]
  [prefix {:coercion coercion
           :muuntaja m/instance
           :swagger {:tags #{:agora}}
           :middleware [parameters/parameters-middleware
                        muuntaja/format-negotiate-middleware
                        muuntaja/format-response-middleware
                        error/exception-middleware
                        muuntaja/format-request-middleware
                        rcoercion/coerce-request-middleware]}
   [""
    {:get {:handler mine-handler
           :operationId "agora-publications-mine"
           ;; `?mine=1` is accepted for forward-compat; the list is always the caller's own
           :parameters {:query [:map
                                [:mine {:optional true}
                                 [:maybe [:string {:max 4}]]]]}
           :summary "The caller's open publications"}
     :post {:handler create-handler
            :middleware throttled
            :operationId "agora-publication-create"
            :parameters {:body [:map
                                [:title
                                 [:string {:min 1
                                           :max 200}]]
                                [:lang {:optional true}
                                 [:maybe [:string {:max 8}]]]]}
            :summary "Open a new publication"}}]
   ["/:id"
    {:get {:handler fetch-handler
           :operationId "agora-publication"
           :parameters {:path [:map [:id [:string {:max 64}]]]}
           :summary "A publication by id"}}]])
