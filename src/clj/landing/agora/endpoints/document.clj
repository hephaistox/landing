(ns landing.agora.endpoints.document
  "HTTP surface for documents. Reads: browse/search a type's cards, read one by id or by its
  (type, name, lang, major) identity, and list a lineage's versions. Writes (session required):
  create a new document, or edit one into a new version (text-only for now — see
  `landing.agora.document.write`). One mount, the type in the path."
  (:require
   [landing.agora.auth                :as auth]
   [landing.agora.db.document         :as db-doc]
   [landing.agora.document.engine     :as engine]
   [landing.agora.document.identity   :as di]
   [landing.agora.document.storage    :as ds]
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

(defn- uid [req] (get-in req [:session :user-id]))

(defn- owned-publication
  "The publication cid to scope a browse/search to — `cid` only when the caller owns it, else nil. The
  active publication comes from the client, so a scoped view may never surface another owner's private
  drafts."
  [req cid]
  (when (seq cid)
    (when-let [p (db-doc/fetch-latest-any :publication cid)]
      (when (= (:owner-id p) (uid req)) cid))))

(defn- coerce-target
  "The structural target TNLR from the request body (an example / counter-example's referenced claim),
  keys coerced to the domain's keywords — JSON sends `:type`/`:lang` as strings. nil when absent."
  [req]
  (when-let [t (get-in req [:body-params :target])]
    (when (seq (:name t))
      (-> (select-keys t [:type :name :lang :major])
          (update :type #(keyword (or % "ki")))
          (update :lang
                  #(some-> %
                           keyword))))))

(defn- create-handler
  "Create a new document of `:type` owned by the caller (text-only). Body:
  `{:kind :title :text :lang :publication-id}`, plus for a `work` its cited author (`:author-id` +
  `:author-name`) and bibliographic `:year`/`:editor`/`:url`, and for an `extract` its `:locator`.
  Created as a draft in the publication (required; 400 when absent). The byline `author` is the cited
  author's name for a work, else the contributor's display name. Returns the created version's
  endpoint view."
  [doc-storage req]
  (if-let [uid (uid req)]
    (let [type (keyword (get-in req [:path-params :type]))
          {:keys
           [kind title text lang publication-id author-id author-name year editor url locator]}
          (:body-params req)
          kind (some-> kind
                       keyword)]
      (if-not (seq publication-id)
        ;; every create happens inside an open publication (no publish-directly path)
        {:status 400
         :body {:error "a publication must be selected"}}
        ;; JSON delivers kind/lang as strings; the write domain works in keywords
        (let [author (if (= kind :work) author-name (:display-name (auth/get-user uid)))
              new-id (write/create! type
                                    uid
                                    author
                                    {:kind kind
                                     :title title
                                     :text text
                                     :lang (some-> lang
                                                   keyword)
                                     :author-id author-id
                                     :year year
                                     :editor editor
                                     :url url
                                     :locator locator
                                     :target (coerce-target req)}
                                    publication-id)]
          {:status 200
           :body (engine/read-by-id doc-storage new-id)})))
    {:status 401
     :body {:error "login required"}}))

(defn- edit-handler
  "Edit document `:id` into a new version (text-only). Body: `{:title :text :kind :publication-id}`,
  plus the bibliographic (`:author-id`/`:year`/`:editor`/`:url`) or `:locator` extras a new value
  overrides. A new minor when the caller owns it, else a fork (new major). Returns the new version's
  endpoint view."
  [doc-storage req]
  (if-let [editor (uid req)]
    (let [id (get-in req [:path-params :id])
          {:keys [title text kind publication-id author-id year url locator]
           biblio-editor :editor}
          (:body-params req)]
      (if-not (seq publication-id)
        ;; every edit happens inside an open publication (the new version is a draft in it)
        {:status 400
         :body {:error "a publication must be selected"}}
        (if-let [new-id (write/edit! id
                                     editor
                                     (:display-name (auth/get-user editor))
                                     {:title title
                                      :text text
                                      :kind (some-> kind
                                                    keyword)
                                      :author-id author-id
                                      :year year
                                      :editor biblio-editor
                                      :url url
                                      :locator locator
                                      :target (coerce-target req)}
                                     publication-id)]
          ;; an edit may rewrite a draft **in place** (same id); drop its stale cache entry so the
          ;; read-back returns the new content
          (do (ds/publish-change! doc-storage new-id)
              {:status 200
               :body (engine/read-by-id doc-storage new-id)})
          {:status 404
           :body {:error "not found"
                  :id id}})))
    {:status 401
     :body {:error "login required"}}))

(defn- delete-handler
  "Delete draft document `:id` (the caller's own, in a publication). Returns the publication cid so
  the client can refresh it."
  [req]
  (if-let [editor (uid req)]
    (if-let [pub-id (write/delete! editor (get-in req [:path-params :id]))]
      {:status 200
       :body {:publication-id pub-id}}
      {:status 404
       :body {:error "not found"}})
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
                   {:keys [lang limit offset q publication]} (get-in req [:parameters :query])
                   ;; `all` (or absent) → every language (the client filters by content language)
                   lang (when-not (contains? #{nil "all"} lang) lang)
                   pub (owned-publication req publication)]
               {:status 200
                :body
                (if (some? q)
                  (engine/search-cards doc-storage type lang q pub)
                  (engine/list-cards doc-storage type lang (or limit 20) (or offset 0) pub))}))
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
                                 [:maybe :int]]
                                [:publication {:optional true}
                                 [:maybe :string]]]}
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
                            :major major}
                       pub (owned-publication req (get-in req [:parameters :query :publication]))]
                   (if-let [d (engine/read-by-major doc-storage ref pub)]
                     {:status 200
                      :body d}
                     {:status 404
                      :body {:error "not found"}})))
      :operationId "agora-read-by-tnlr"
      :parameters {:path [:map [:type :string] [:name :string] [:lang :string] [:major :int]]
                   :query [:map
                           [:publication {:optional true}
                            [:maybe :string]]]}
      :summary
      "A document by its identity — latest published minor; a `*` lang uses the request's language"}}]
   ["/:type/:id"
    {:get {:handler (fn [req]
                      (let [id (get-in req [:parameters :path :id])
                            pub (owned-publication req
                                                   (get-in req [:parameters :query :publication]))]
                        (if-let [d (engine/read-by-id doc-storage id pub)]
                          {:status 200
                           :body d}
                          {:status 404
                           :body {:error "not found"
                                  :id id}})))
           :operationId "agora-read-by-id"
           :parameters {:path [:map [:type :string] [:id :string]]
                        :query [:map
                                [:publication {:optional true}
                                 [:maybe :string]]]}
           :summary "A document by id"}
     :post {:handler (fn [req] (edit-handler doc-storage req))
            :operationId "agora-edit-document"
            :summary "Edit a document into a new version (session required)"}
     :delete {:handler delete-handler
              :operationId "agora-delete-document"
              :summary "Delete a draft document (owner-only)"}}]
   ["/:type/:id/versions"
    {:get {:handler (fn [req]
                      {:status 200
                       :body (db-doc/versions-of-id (get-in req [:parameters :path :id]))})
           :operationId "agora-document-versions"
           :parameters {:path [:map [:type :string] [:id :string]]}
           :summary "Every version of the lineage containing this id (admin version picker)"}}]])
