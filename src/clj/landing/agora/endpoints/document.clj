(ns landing.agora.endpoints.document
  "HTTP surface for documents. Reads: browse/search a type's cards, read one by id or by its
  (type, name, lang, major) identity, and list a lineage's versions. Writes (session required):
  create a new document, or edit one into a new version (text-only for now — see
  `landing.agora.document.write`). One mount, the type in the path."
  (:require
   [landing.agora.db.document         :as db-doc]
   [landing.agora.document.engine     :as engine]
   [landing.agora.document.identity   :as di]
   [landing.agora.document.storage    :as ds]
   [landing.agora.document.write      :as write]
   [landing.agora.endpoints.throttle  :as throttle]
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

(def ^:private write-body
  "Request body for create/edit. `publication-id` is **required** (non-empty): every write is a draft
  gathered by an open publication, so coercion rejects a write with no publication before the handler
  runs. The rest are optional — a `work`'s cited author (`:attributed-author-id`, the person whose
  name the byline is derived from) + bibliographic fields, an `extract`'s `:locator`. The map is open,
  so the structural input (an extract's `:work`, an illustration/counter-example's `:target`) rides
  through to the handler untouched."
  [:map
   [:publication-id [:string {:min 1}]]
   [:kind {:optional true}
    [:maybe :string]]
   [:title {:optional true}
    [:maybe [:string {:max 500}]]]
   [:text {:optional true}
    [:maybe [:string {:max 20000}]]]
   [:lang {:optional true}
    [:maybe :string]]
   [:attributed-author-id {:optional true}
    [:maybe :string]]
   [:year {:optional true}
    [:maybe :int]]
   [:editor {:optional true}
    [:maybe :string]]
   [:url {:optional true}
    [:maybe :string]]
   [:locator {:optional true}
    [:maybe :string]]])

(def ^:private error-body
  "The shape of every error response: `{:error \"...\"}` (open — some carry an extra `:id`)."
  [:map [:error :string]])

(defn- uid [req] (get-in req [:session :user-id]))

(defn- owned-publication
  "The publication cid to scope a browse/search to — `cid` only when the caller owns it, else nil. The
  active publication comes from the client, so a scoped view may never surface another owner's private
  drafts."
  [req cid]
  (when (seq cid)
    (when-let [p (db-doc/fetch-latest-any :publication cid)]
      (when (= (:owner-id p) (uid req)) cid))))

(defn- draft-hidden?
  "True when `id` resolves to a **draft the caller may not see** — a draft owned by someone else (or
  requested anonymously). Published documents, and the owner's own drafts, are visible. Unknown ids
  return nil (the caller's read then 404s normally). Drafts are private to their owner: they must
  never leak through an id read, a versions list, or a successor edge."
  [req id]
  (when-let [raw (db-doc/fetch-id id)] (and (:draft raw) (not= (uid req) (:owner-id raw)))))

(defn- coerce-structural-input
  "The structural single input TNLR from the request body — named by what it is: an **extract**'s
  `:work` (the œuvre it quotes), or an **illustration**/**counter-example**'s `:target` (the claim it
  exemplifies/refutes). Keys coerced to the domain's keywords — JSON sends `:type`/`:lang` as strings.
  nil when absent."
  [req]
  (when-let [t (or (get-in req [:body-params :work]) (get-in req [:body-params :target]))]
    (when (seq (:name t))
      (-> (select-keys t [:type :name :lang :major])
          (update :type #(keyword (or % "ki")))
          (update :lang
                  #(some-> %
                           keyword))))))

(defn- create-handler
  "Create a new document of `:type` owned by the caller (text-only). Body is `write-body`:
  `:publication-id` (required — coercion rejects a create with no open publication), `:kind`/`:title`/
  `:text`/`:lang`, plus for a `work` its cited author (`:attributed-author-id`) and bibliographic
  `:year`/`:editor`/`:url`, and for an `extract` its `:locator`. Returns the created version's endpoint
  view."
  [doc-storage req]
  (let [uid (uid req)
        type (keyword (get-in req [:path-params :type]))
        {:keys [kind title text lang publication-id attributed-author-id year editor url locator]}
        (:body-params req)
        kind (some-> kind
                     keyword)]
    (cond
      (nil? uid) {:status 401
                  :body {:error "login required"}}
      ;; only KIs and articles are authored here — `publication` (and any other object type) has its
      ;; own surface and invariants, so the type wildcard may not forge one
      (not (contains? #{:ki :article} type)) {:status 400
                                              :body {:error "unknown document type"}}
      ;; a write may only target a publication the caller owns — never inject a draft into another
      ;; user's publication
      (not (owned-publication req publication-id)) {:status 403
                                                    :body {:error "not your publication"}}
      :else
      ;; JSON delivers kind/lang as strings; the write domain works in keywords. The cited author
      ;; (`attributed-author-id`) is honoured **only for a `work`** — for every other kind the byline
      ;; is the owner, so a client can't point a document's byline at another person. `owner-id` stays
      ;; the accountability concept, distinct from the displayed attribution.
      (let [new-id (write/create! type
                                  uid
                                  {:kind kind
                                   :title title
                                   :text text
                                   :lang (some-> lang
                                                 keyword)
                                   :author-id (when (= kind :work) attributed-author-id)
                                   :year year
                                   :editor editor
                                   :url url
                                   :locator locator
                                   :target (coerce-structural-input req)}
                                  publication-id)]
        {:status 200
         :body (engine/read-by-id doc-storage new-id)}))))

(defn- edit-handler
  "Edit document `:id` into a new version (text-only). Body is `write-body`: `:publication-id`
  (required — coercion rejects an edit with no open publication), `:title`/`:text`/`:kind`, plus the
  bibliographic (`:attributed-author-id`/`:year`/`:editor`/`:url`) or `:locator` extras a new value
  overrides. A new minor when the caller owns it, else a fork (new major). Returns the new version's
  endpoint view."
  [doc-storage req]
  (let [editor (uid req)
        id (get-in req [:path-params :id])
        {:keys [title text kind publication-id attributed-author-id year url locator]
         biblio-editor :editor}
        (:body-params req)
        kw-kind (some-> kind
                        keyword)]
    (cond
      (nil? editor) {:status 401
                     :body {:error "login required"}}
      ;; the new version is a draft in the caller's own publication — never another user's
      (not (owned-publication req publication-id)) {:status 403
                                                    :body {:error "not your publication"}}
      :else
      ;; the cited author is honoured only for a `work` (see create-handler); nil otherwise carries
      ;; the existing value forward
      (if-let [new-id (write/edit! id
                                   editor
                                   {:title title
                                    :text text
                                    :kind kw-kind
                                    :author-id (when (= kw-kind :work) attributed-author-id)
                                    :year year
                                    :editor biblio-editor
                                    :url url
                                    :locator locator
                                    :target (coerce-structural-input req)}
                                   publication-id)]
        ;; an edit may rewrite a draft **in place** (same id); drop its stale cache entry so the
        ;; read-back returns the new content
        (do (ds/publish-change! doc-storage new-id)
            {:status 200
             :body (engine/read-by-id doc-storage new-id)})
        {:status 404
         :body {:error "not found"
                :id id}}))))

(defn- delete-handler
  "Delete draft document `:id` (the caller's own). The `:publication-id` query param is **required** and
  must be the draft's own publication — a delete is scoped to the publication the caller is working in.
  Returns the publication cid so the client can refresh it."
  [req]
  (if-let [editor (uid req)]
    (if-let [pub-id (write/delete! editor
                                   (get-in req [:path-params :id])
                                   (get-in req [:parameters :query :publication-id]))]
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
           :swagger {:tags #{:agora-documents}}
           :responses {500 {:description "Unexpected server error"}}
           :middleware mw}
   ["/:type"
    {:get {:handler
           (fn [req]
             (let [{:keys [type]} (get-in req [:parameters :path])
                   {:keys [lang limit offset q publication-id]} (get-in req [:parameters :query])
                   ;; `all` (or absent) → every language (the client filters by content language)
                   lang (when-not (contains? #{nil "all"} lang) lang)
                   pub (owned-publication req publication-id)]
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
                                [:publication-id {:optional true}
                                 [:maybe :string]]]}
           :responses {200 {:description "Matching document cards"}}
           :summary "Browse documents of a type, or search them with `?q=`"}
     :post {:handler (fn [req] (create-handler doc-storage req))
            :operationId "agora-create-document"
            :middleware [(:middleware-fn throttle/authoring-rate-limiter)]
            :parameters {:path [:map [:type :string]]
                         :body write-body}
            :responses {200 {:description "The created version's endpoint view"}
                        400 {:description "Unknown document type"
                             :body error-body}
                        401 {:description "Login required"
                             :body error-body}
                        403 {:description "Not your publication"
                             :body error-body}
                        429 {:description "Rate limit exceeded"}}
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
                       pub (owned-publication req
                                              (get-in req [:parameters :query :publication-id]))]
                   (if-let [d (engine/read-by-major doc-storage ref pub)]
                     {:status 200
                      :body d}
                     {:status 404
                      :body {:error "not found"}})))
      :operationId "agora-read-by-tnlr"
      :parameters {:path [:map [:type :string] [:name :string] [:lang :string] [:major :int]]
                   :query [:map
                           [:publication-id {:optional true}
                            [:maybe :string]]]}
      :responses {200 {:description "The document (latest published minor)"}
                  404 {:description "No published version for this identity"
                       :body error-body}}
      :summary
      "A document by its identity — latest published minor; a `*` lang uses the request's language"}}]
   ["/:type/:id"
    {:get {:handler
           (fn [req]
             (let [id (get-in req [:parameters :path :id])
                   pub (owned-publication req (get-in req [:parameters :query :publication-id]))]
               (if-let [d (and (not (draft-hidden? req id)) (engine/read-by-id doc-storage id pub))]
                 {:status 200
                  :body d}
                 {:status 404
                  :body {:error "not found"
                         :id id}})))
           :operationId "agora-read-by-id"
           :parameters {:path [:map [:type :string] [:id :string]]
                        :query [:map
                                [:publication-id {:optional true}
                                 [:maybe :string]]]}
           :responses {200 {:description "The exact version"}
                       404 {:description "No such version"
                            :body error-body}}
           :summary "A document by id"}
     :post {:handler (fn [req] (edit-handler doc-storage req))
            :operationId "agora-edit-document"
            :middleware [(:middleware-fn throttle/authoring-rate-limiter)]
            :parameters {:path [:map [:type :string] [:id :string]]
                         :body write-body}
            :responses {200 {:description "The new version's endpoint view"}
                        401 {:description "Login required"
                             :body error-body}
                        403 {:description "Not your publication"
                             :body error-body}
                        404 {:description "No such document"
                             :body error-body}
                        429 {:description "Rate limit exceeded"}}
            :summary "Edit a document into a new version (session required)"}
     :delete {:handler delete-handler
              :operationId "agora-delete-document"
              :middleware [(:middleware-fn throttle/authoring-rate-limiter)]
              :parameters {:path [:map [:type :string] [:id :string]]
                           :query [:map [:publication-id [:string {:min 1}]]]}
              :responses {200 {:description "Deleted; returns the publication cid"}
                          401 {:description "Login required"
                               :body error-body}
                          404 {:description
                               "Not a deletable draft, not the owner, or wrong publication"
                               :body error-body}
                          429 {:description "Rate limit exceeded"}}
              :summary "Delete a draft document (owner-only, scoped to its publication)"}}]
   ["/:type/:id/versions"
    {:get {:handler (fn [req]
                      ;; drafts are private: a non-owner sees only the published minors, never a
                      ;; draft's id or that one is in flight
                      (let [id (get-in req [:parameters :path :id])
                            versions (db-doc/versions-of-id id)
                            owner? (= (uid req) (:owner-id (db-doc/fetch-id id)))]
                        {:status 200
                         :body (if owner? versions (remove :draft versions))}))
           :operationId "agora-document-versions"
           :parameters {:path [:map [:type :string] [:id :string]]}
           :responses {200 {:description "Every version of the lineage"}}
           :summary "Every version of the lineage containing this id (admin version picker)"}}]])
