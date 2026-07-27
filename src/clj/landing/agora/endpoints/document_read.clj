(ns landing.agora.endpoints.document-read
  "HTTP read routes for documents: browse a type's cards, and read one document by id or by its
  (type, name, lang, major) identity. One mount, with the type in the path (see
  `document-read-routes`)."
  (:require
   [clojure.set                       :as set]
   [landing.agora.document.engine     :as engine]
   [landing.agora.document.identity   :as di]
   [landing.language                  :as language]
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
  "Read routes for documents: `GET <prefix>/:type?lang=` lists browse cards,
  `GET <prefix>/:type/:name/:lang/:major` reads a lineage's latest published minor by its identity
  (the permalink), and `GET <prefix>/:type/:id` reads an exact version — all returning the same
  endpoint view (404 when unknown). The two reads never collide: one is four path segments, the other
  two. One mount serves every type via the `:type` wildcard; nesting under `/agora/api/documents`
  keeps that wildcard within document types, so it never overlaps the sibling
  `/agora/api/{author,people,publication}` routes."
  [doc-storage prefix]
  [prefix {:coercion coercion
           :muuntaja m/instance
           :swagger {:tags #{:agora}}
           :middleware mw}
   ["/:type"
    {:get {:handler
           (fn [req]
             (let [{:keys [type]} (get-in req [:parameters :path])
                   {:keys [lang limit offset q]} (get-in req [:parameters :query])
                   lang (or lang "fr")]
               {:status 200
                :body (mapv
                       serve
                       (if (some? q)
                         (engine/search-cards doc-storage type lang q)
                         (engine/list-cards doc-storage type lang (or limit 20) (or offset 0))))}))
           :operationId "agora-list-documents"
           :parameters {:path [:map [:type :string]]
                        :query [:map
                                [:lang {:optional true}
                                 [:maybe :string]]
                                [:q {:optional true}
                                 [:maybe :string]]
                                [:limit {:optional true}
                                 [:maybe :int]]
                                [:offset {:optional true}
                                 [:maybe :int]]]}
           :summary "Browse documents of a type, or search them with `?q=`"}}]
   ["/:type/:name/:lang/:major"
    {:get
     {:handler (fn [req]
                 (let [{:keys [type name lang major]} (get-in req [:parameters :path])
                       ;; `*` is a wildcard content-language — resolve it from the request
                       ;; (cookie → Accept-Language → default) so a language-neutral link
                       ;; lands the reader on their own language.
                       lang (if (= lang "*") (language/pick-lang req) lang)
                       ;; the name segment is `<cid>~<slug>` (or bare cid) — resolve by cid
                       ref {:type (keyword type)
                            :name (di/cid-of name)
                            :lang (keyword lang)
                            :major major}]
                   (if-let [d (engine/read-by-major doc-storage ref)]
                     {:status 200
                      :body (serve d)}
                     {:status 404
                      :body {:error "not found"}})))
      :operationId "agora-read-by-tnlr"
      :parameters {:path [:map [:type :string] [:name :string] [:lang :string] [:major :int]]}
      :summary
      "A document by its identity — latest published minor; a `*` lang uses the request's language"}}]
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
