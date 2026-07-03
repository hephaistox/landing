(ns landing.agora.endpoints.ki
  "HTTP route for reading a single Knowledge Item: GET /api/ki/:id.

  No auth, no pagination. Content negotiation (JSON/EDN) via muuntaja. The :id
  path parameter is declared via malli coercion so it is documented in Swagger
  (input box) rather than called as a literal {id}."
  (:require
   [landing.agora.ki                  :as ki]
   [muuntaja.core                     :as m]
   [reitit.coercion.malli             :refer [coercion]]
   [reitit.ring.coercion              :as rcoercion]
   [reitit.ring.middleware.exception  :as exception]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(def ^:private ki-type-enum
  [:enum "derived" "verifiable-claim" "postulate" "stance" "belief" "credo"])

(def ki-handler
  "Return the KI identified by the :id path param, or 404 if it does not exist."
  (fn [req]
    (let [id (get-in req [:parameters :path :id])]
      (if-let [ki (ki/fetch-ki id)]
        {:status 200
         :body ki}
        {:status 404
         :body {:error "KI not found"
                :id id}}))))

(def edit-ki-handler
  "Create a new minor version of the KI identified by :id from the request body
  (type + output statement). 201 with the new KI, or 404 if the source is unknown.
  Not auth-gated yet — OAuth arrives in #38."
  (fn [req]
    (let [id (get-in req [:parameters :path :id])
          edit (get-in req [:parameters :body])]
      (if-let [ki (ki/edit-ki id edit)]
        {:status 201
         :body ki}
        {:status 404
         :body {:error "KI not found"
                :id id}}))))

(defn edit-ki-route
  [prefix]
  [prefix
   {:post {:coercion coercion
           :handler edit-ki-handler
           :muuntaja m/instance
           :operationId "agora-edit-ki"
           :parameters {:path [:map [:id :string]]
                        :body [:map [:type ki-type-enum] [:output-statement :string]]}
           :summary "Edit a KI — produces a new minor version (immutable)"
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
                        ;; coercing request parameters (path :id + body)
                        rcoercion/coerce-request-middleware]}}])

(defn ki-route
  [prefix]
  [prefix
   {:get {:coercion coercion
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
