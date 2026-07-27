(ns landing.agora.endpoints.document-read
  "HTTP read routes for documents: browse a type's cards and read a document by id. One mount, with
  the type in the path (see `document-read-routes`)."
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
  "Read routes for documents: `GET <prefix>/:type?lang=` lists browse cards, `GET <prefix>/:type/:id`
  returns a document's endpoint view (404 when unknown). One mount serves every type via the `:type`
  wildcard; nesting under `/agora/api/documents` keeps that wildcard within document types, so it
  never overlaps the sibling `/agora/api/{author,people,publication}` routes."
  [doc-storage prefix]
  [prefix {:coercion coercion
           :muuntaja m/instance
           :swagger {:tags #{:agora}}
           :middleware mw}
   ["/:type"
    {:get {:handler
           (fn [req]
             (let [{:keys [type]} (get-in req [:parameters :path])
                   {:keys [lang limit offset]} (get-in req [:parameters :query])]
               {:status 200
                :body
                (mapv
                 serve
                 (engine/list-cards doc-storage type (or lang "fr") (or limit 20) (or offset 0)))}))
           :operationId "agora-list-documents"
           :parameters {:path [:map [:type :string]]
                        :query [:map
                                [:lang {:optional true}
                                 [:maybe :string]]
                                [:limit {:optional true}
                                 [:maybe :int]]
                                [:offset {:optional true}
                                 [:maybe :int]]]}
           :summary "Browse documents of a type"}}]
   ["/:type/:id"
    {:get {:handler (fn [req]
                      (let [id (get-in req [:parameters :path :id])]
                        (if-let [d (engine/read-by-id doc-storage id)]
                          {:status 200
                           :body (serve d)}
                          {:status 404
                           :body {:error "not found"
                                  :id id}})))
           :operationId "agora-read-by-id"
           :parameters {:path [:map [:type :string] [:id :string]]}
           :summary "A document by id"}}]])
