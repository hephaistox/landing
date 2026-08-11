(ns landing.agora.endpoints.document
  "HTTP surface for documents. Reads: browse/search a type's cards, read one by id or by its
  (type, name, lang, major) identity, and list a lineage's versions. Writes (session required):
  create a new document, or edit one into a new version (text-only for now — see
  `landing.agora.document.write`). One mount, the type in the path."
  (:require
   [clojure.set                       :as set]
   [landing.agora.auth                :as auth]
   [landing.agora.db.document         :as db-doc]
   [landing.agora.document.engine     :as engine]
   [landing.agora.document.identity   :as di]
   [landing.agora.document.write      :as write]
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

(defn- uid [req] (get-in req [:session :user-id]))

(defn- create-handler
  "Create a new document of `:type` owned by the caller (text-only). Body:
  `{:kind :title :text :lang :publication-id}` — created as a draft in the publication (required; 400
  when absent). Returns the created version's endpoint view."
  [doc-storage req]
  (if-let [uid (uid req)]
    (let [type (keyword (get-in req [:path-params :type]))
          {:keys [kind title text lang publication-id]} (:body-params req)]
      (if-not (seq publication-id)

        ;; every create happens inside an open publication (no publish-directly path)
        {:status 400
         :body {:error "a publication must be selected"}}
        ;; JSON delivers kind/lang as strings; the write domain works in keywords
        (let [new-id (write/create! type
                                    uid
                                    (:display-name (auth/get-user uid))
                                    {:kind (some-> kind
                                                   keyword)
                                     :title title
                                     :text text
                                     :lang (some-> lang
                                                   keyword)}
                                    publication-id)]
          {:status 200
           :body (serve (engine/read-by-id doc-storage new-id))})))
    {:status 401
     :body {:error "login required"}}))

(defn- edit-handler
  "Edit document `:id` into a new version (text-only). Body: `{:title :text :publication-id}`. A new
  minor when the caller owns it, else a fork (new major). Returns the new version's endpoint view."
  [doc-storage req]
  (if-let [editor (uid req)]
    (let [id (get-in req [:path-params :id])
          {:keys [title text publication-id]} (:body-params req)]
      (if-not (seq publication-id)
        ;; every edit happens inside an open publication (the new version is a draft in it)
        {:status 400
         :body {:error "a publication must be selected"}}
        (if-let [new-id (write/edit! id
                                     editor
                                     (:display-name (auth/get-user editor))
                                     {:title title
                                      :text text}
                                     publication-id)]
          {:status 200
           :body (serve (engine/read-by-id doc-storage new-id))}
          {:status 404
           :body {:error "not found"
                  :id id}})))
    {:status 401
     :body {:error "login required"}}))

(defn document-routes
  "Document routes under `<prefix>` (the type in the path). Reads: `GET /:type?lang=&q=` (browse or
  search), `GET /:type/:name/:lang/:major` (permalink — latest published minor; `*` lang = the
  request's), `GET /:type/:id` (exact version), `GET /:type/:id/versions` (admin picker). Writes
  (session required): `POST /:type` (create), `POST /:type/:id` (edit → new version). One mount under
  `/agora/api/documents`, so the `:type` wildcard never overlaps the sibling API routes."
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
           :summary "Browse documents of a type, or search them with `?q=`"}
     :post {:handler (fn [req] (create-handler doc-storage req))
            :operationId "agora-create-document"
            :summary "Create a new document (session required)"}}]
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
           :summary "A document by id"}
     :post {:handler (fn [req] (edit-handler doc-storage req))
            :operationId "agora-edit-document"
            :summary "Edit a document into a new version (session required)"}}]
   ["/:type/:id/versions"
    {:get {:handler (fn [req]
                      {:status 200
                       :body (db-doc/versions-of-id (get-in req [:parameters :path :id]))})
           :operationId "agora-document-versions"
           :parameters {:path [:map [:type :string] [:id :string]]}
           :summary "Every version of the lineage containing this id (admin version picker)"}}]])
