(ns landing.agora.endpoints.ki
  "HTTP route for reading a single Knowledge Item: GET /api/ki/:id.

  No auth, no pagination. Content negotiation (JSON/EDN) via muuntaja. The :id
  path parameter is declared via malli coercion so it is documented in Swagger
  (input box) rather than called as a literal {id}."
  (:require
   [landing.agora.ki :as ki]
   [muuntaja.core :as m]
   [reitit.coercion.malli :refer [coercion]]
   [reitit.ring.coercion :as rcoercion]
   [reitit.ring.middleware.exception :as exception]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(def ki-handler
  "Return the KI identified by the :id path param, or 404 if it does not exist."
  (fn [req]
    (let [id (get-in req [:parameters :path :id])]
      (if-let [ki (ki/fetch-ki id)]
        {:status 200 :body ki}
        {:status 404 :body {:error "KI not found" :id id}}))))

(defn ki-route
  [prefix]
  [prefix {:get {:coercion coercion
                 :handler ki-handler
                 :muuntaja m/instance
                 :operationId "agora-ki-by-id"
                 :parameters {:path [:map [:id :string]]}
                 :summary "Fetch a single Knowledge Item by id"
                 :swagger {:tags #{:agora}}
                 :middleware [;; query-params & form-params
                              parameters/parameters-middleware
                              ;; content-negotiation
                              muuntaja/format-negotiate-middleware
                              ;; encoding response body
                              muuntaja/format-response-middleware
                              ;; exception handling
                              exception/exception-middleware
                              ;; decoding request body
                              muuntaja/format-request-middleware
                              ;; coercing request parameters (path :id)
                              rcoercion/coerce-request-middleware]}}])
