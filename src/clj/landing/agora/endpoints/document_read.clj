(ns landing.agora.endpoints.document-read
  "HTTP by-id read route. `GET <prefix>/:id` returns a document's endpoint view.

  Mounted once per type, at literal prefixes. A `/:type/:id` wildcard would swallow the sibling
  `/agora/api/{author,publication,source}/:id` routes."
  (:require
   [clojure.set                       :as set]
   [landing.agora.document.engine     :as engine]
   [muuntaja.core                     :as m]
   [reitit.coercion.malli             :refer [coercion]]
   [reitit.ring.coercion              :as rcoercion]
   [reitit.ring.middleware.exception  :as exception]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(def ^:private mw
  [parameters/parameters-middleware
   muuntaja/format-negotiate-middleware
   muuntaja/format-response-middleware
   exception/exception-middleware
   muuntaja/format-request-middleware
   rcoercion/coerce-request-middleware])

(defn- serve
  "Rename a document's `:source` `:source-id` to `:id` for the wire (the client reads `:id`)."
  [doc]
  (cond-> doc
    (:source doc) (update :source set/rename-keys {:source-id :id})))

(defn document-read-routes
  "By-id read route under `prefix`, reading from the injected `doc-storage`. `GET <prefix>/:id`
  returns the endpoint view, or 404."
  [doc-storage prefix]
  [prefix {:coercion coercion
           :muuntaja m/instance
           :swagger {:tags #{:agora}}
           :middleware mw}
   ["/:id"
    {:get {:handler (fn [req]
                      (let [id (get-in req [:parameters :path :id])]
                        (if-let [d (engine/read-by-id doc-storage id)]
                          {:status 200
                           :body (serve d)}
                          {:status 404
                           :body {:error "not found"
                                  :id id}})))
           :operationId (str "agora-read-by-id" prefix)
           :parameters {:path [:map [:id :string]]}
           :summary "A document by id (new read stack)"}}]])
