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

(def ^:private input-ref-schema
  "Body for add/drop input: a Major-level KI reference (name + major; identity's
  T is the object type `ki`)."
  [:map [:name :string] [:major :int]])

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

(def search-ki-handler
  "With ?q= present, search KIs by name; otherwise return the discoverability
  list (recent KIs, latest minor)."
  (fn [req]
    (let [q (get-in req [:parameters :query :q])]
      {:status 200
       :body (if (seq q) (ki/search-kis q) (ki/list-kis))})))

(def create-ki-handler
  "Create a new KI (major 1, minor 0) from the request body. 201 with the KI."
  (fn [req]
    {:status 201
     :body (ki/create-ki (get-in req [:parameters :body]))}))

(defn ki-collection-route
  "The KI collection: GET searches by name (?q=), POST creates a new KI."
  [prefix]
  [prefix
   {:coercion coercion
    :muuntaja m/instance
    :swagger {:tags #{:agora}}
    :middleware [parameters/parameters-middleware
                 muuntaja/format-negotiate-middleware
                 muuntaja/format-response-middleware
                 exception/exception-middleware
                 muuntaja/format-request-middleware
                 rcoercion/coerce-request-middleware]
    :get {:handler search-ki-handler
          :operationId "agora-search-kis"
          :parameters {:query [:map
                               [:q {:optional true}
                                :string]]}
          :summary "Search KIs by name"}
    :post {:handler create-ki-handler
           :operationId "agora-create-ki"
           :parameters {:body
                        [:map [:name :string] [:type ki-type-enum] [:output-statement :string]]}
           :summary "Create a new KI (major 1, minor 0)"}}])

(def add-input-handler
  "Add an input link to the KI :id from the body ref. 200 with the updated KI."
  (fn [req]
    (let [id (get-in req [:parameters :path :id])
          input (get-in req [:parameters :body])]
      (if-let [ki (ki/add-input id input)]
        {:status 200
         :body ki}
        {:status 404
         :body {:error "KI not found"
                :id id}}))))

(def drop-input-handler
  "Drop an input link from the KI :id given the body ref. 200 with the updated KI."
  (fn [req]
    (let [id (get-in req [:parameters :path :id])
          input (get-in req [:parameters :body])]
      (if-let [ki (ki/drop-input id input)]
        {:status 200
         :body ki}
        {:status 404
         :body {:error "KI not found"
                :id id}}))))

(defn inputs-route
  "Manage a KI's input links: POST adds one, DELETE removes one. Body is a
  Major-level KI reference {:name :major}."
  [prefix]
  [prefix
   {:coercion coercion
    :muuntaja m/instance
    :swagger {:tags #{:agora}}
    :middleware [parameters/parameters-middleware
                 muuntaja/format-negotiate-middleware
                 muuntaja/format-response-middleware
                 exception/exception-middleware
                 muuntaja/format-request-middleware
                 rcoercion/coerce-request-middleware]
    :post {:handler add-input-handler
           :operationId "agora-add-input"
           :parameters {:path [:map [:id :string]]
                        :body input-ref-schema}
           :summary "Add an input link to a KI"}
    :delete {:handler drop-input-handler
             :operationId "agora-drop-input"
             :parameters {:path [:map [:id :string]]
                          :body input-ref-schema}
             :summary "Drop an input link from a KI"}}])

(def by-major-handler
  "Return the latest-minor KI of a (name, major) lineage — the permanent public
  identity — or 404. Records a visit (drives discoverability weighting, #36)."
  (fn [req]
    (let [{ki-name :name
           ki-major :major}
          (get-in req [:parameters :path])]
      (if-let [ki (ki/fetch-ki-by-major ki-name ki-major)]
        (do (ki/record-visit ki-name ki-major)
            {:status 200
             :body ki})
        {:status 404
         :body {:error "KI not found"
                :name ki-name
                :major ki-major}}))))

(defn by-major-route
  "Public permanent identity: GET /api/ki/by/:name/:major → latest minor."
  [prefix]
  [prefix {:get {:coercion coercion
                 :handler by-major-handler
                 :muuntaja m/instance
                 :operationId "agora-ki-by-major"
                 :parameters {:path [:map [:name :string] [:major :int]]}
                 :summary "Fetch a KI's latest minor by (name, major)"
                 :swagger {:tags #{:agora}}
                 :middleware [parameters/parameters-middleware
                              muuntaja/format-negotiate-middleware
                              muuntaja/format-response-middleware
                              exception/exception-middleware
                              muuntaja/format-request-middleware
                              rcoercion/coerce-request-middleware]}}])

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
