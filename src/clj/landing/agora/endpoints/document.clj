(ns landing.agora.endpoints.document
  "One generic HTTP route set over the document engine (landing.agora.document),
  mounted once per object type — `(document-routes \"ki\" \"/agora/api/ki\")`,
  `(document-routes \"article\" \"/agora/api/article\")`. Every type gets the full
  surface: list/search, create, by-permanent-identity, by-id, edit, translate and
  input (add/drop). The request surface is **generated identically** for every type
  (`config-for`); the only per-type value is which kinds it accepts (`kind-enum-for`).

  The `/translate` machine-translation *suggestion* (stateless authoring aid, not a
  document op) is a standalone route here too."
  (:require
   [landing.agora.document            :as document]
   [landing.agora.document-identity   :as di]
   [landing.agora.document-kind       :as dk]
   [landing.agora.endpoints.throttle  :as throttle]
   [landing.agora.translate           :as translate]
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

;; Size-bounded field schemas — cap authored text so one request can't store an
;; unbounded blob (storage/DoS abuse) or smuggle an oversized slug into a URL.
(def ^:private name-schema
  [:string {:min 1
            :max 200}])
(def ^:private title-schema
  [:string {:min 1
            :max 200}])
(def ^:private text-schema
  [:string {:min 1
            :max 50000}])
(def ^:private lang-schema
  [:string {:max 8}])
(defn- kind-enum-for
  "A malli enum of the kind names valid for object type `object-type` (KI kinds vs article
  kinds are disjoint, so the enum is per-type — a KI can't be tagged `explainer`, nor an
  article `inference`)."
  [object-type]
  (into [:enum] (map name) (dk/kind-ids-of object-type)))
(def ^:private input-ref-schema [:map [:name name-schema] [:major :int]])

;; the publication a mutation is authored in (an `AGORA_DOCUMENT.id`, `type=publication`),
;; tagged onto every version it mints — optional until authoring-in-a-publication is enforced
(def ^:private publication-id-schema
  [:maybe [:string {:max 64}]])

;; `:source` — only a `kind=source` KI (a quotation) carries one: a reference to its shared
;; **source** work (`AGORA_SOURCE`) plus this quotation's own locator (page/chapter/line/entry).
;; The client sends `{:source-id :locator}`; a blank `:source-id` clears it.
(def ^:private source-schema
  [:map
   [:source-id [:string {:max 64}]]
   [:locator {:optional true}
    [:maybe [:string {:max 500}]]]])

;; `:quotes` — the `kind=source` KIs this document quotes. A source is quoted as an **input
;; edge only** (never written into the prose; see `dk/kind-quotes-in-text?`), so it rides
;; here rather than as a `[[ki:…]]` token. `document/create`/`edit` merge these into `:inputs`.
;; The client resubmits the full list on every edit (they aren't in the text to re-derive).
(def ^:private quotes-schema
  [:vector {:max 50}
   input-ref-schema])

(def ^:private throttled [(:middleware-fn throttle/authoring-rate-limiter)])

(defn- uid [req] (get-in req [:session :user-id]))
(def ^:private unauthorized
  {:status 401
   :body {:error "login required"}})

(defn- config-for
  "Per-type request shaping, **generated** so every object type gets the identical surface —
  the only per-type value is which kinds it accepts (`kind-enum-for`). `:list` is the no-query
  discover feed; the `*-body` malli schemas validate the request. The validated body is handed
  to the engine as-is (coercion strips unknown keys, so the schema *is* the whitelist, and the
  engine keeps only the content keys it recognizes — see `document/create`/`edit`/`translate`).
  All prose is `:text` (statement / body, same cap); every document carries a `:kind`."
  [object-type]
  (let [kind-enum (kind-enum-for object-type)]
    {:list document/list-recent
     :create-body [:map
                   [:name {:optional true}
                    name-schema]
                   [:title title-schema]
                   [:kind kind-enum]
                   [:lang {:optional true}
                    lang-schema]
                   [:text text-schema]
                   [:source {:optional true}
                    source-schema]
                   [:quotes {:optional true}
                    quotes-schema]
                   [:publication-id {:optional true}
                    publication-id-schema]]
     :edit-body [:map
                 [:title title-schema]
                 [:kind kind-enum]
                 [:text text-schema]
                 [:source {:optional true}
                  source-schema]
                 [:quotes {:optional true}
                  quotes-schema]
                 [:publication-id {:optional true}
                  publication-id-schema]]
     :translate-body [:map
                      [:lang lang-schema]
                      [:title {:optional true}
                       [:maybe [:string {:max 200}]]]
                      [:text {:optional true}
                       text-schema]
                      [:publication-id {:optional true}
                       publication-id-schema]]}))

(def ^:private configs
  "object type (string) → its request-shaping config (see `config-for`)."
  {"ki" (config-for "ki")
   "article" (config-for "article")})

(defn- not-found
  [& {:as extra}]
  {:status 404
   :body (merge {:error "not found"} extra)})

(defn document-routes
  "The full route set for object `type` under `prefix`."
  [type prefix]
  (let [{:keys [list create-body edit-body translate-body]} (configs type)
        body #(get-in % [:parameters :body])
        path-id #(get-in % [:parameters :path :id])
        ;; the publication the caller is authoring in (nil when authored outside one), tagged
        ;; onto every version this mutation mints as permanent provenance
        pub-id #(get-in % [:parameters :body :publication-id])
        list-h (fn [req]
                 (let [q (get-in req [:parameters :query :q])
                       lang (or (get-in req [:parameters :query :lang]) language/default-lang)
                       ;; `?drafts=1` includes unpublished drafts in the discover feed (the
                       ;; owner-facing toggle); search stays published-only.
                       drafts? (boolean (#{"1" "true"} (get-in req [:parameters :query :drafts])))]
                   {:status 200
                    :body (if (seq q) (document/search type q lang) (list type lang drafts?))}))
        create-h (fn [req]
                   (if-let [u (uid req)]
                     {:status 201
                      :body (document/create type u (body req) (pub-id req))}
                     unauthorized))
        by-major-h (fn [req]
                     (let [{n :name
                            mj :major}
                           (get-in req [:parameters :path])
                           ;; the path segment is the permalink key `<cid>~<slug>` (or a bare
                           ;; cid) — resolve by the immutable cid, ignoring the slug
                           n (di/cid-of n)
                           lang (or (get-in req [:parameters :query :lang]) language/default-lang)
                           ;; `?publication=<id>` resolves within a publication (its own draft of
                           ;; the lineage else latest published); absent → classical public read
                           pub (get-in req [:parameters :query :publication])
                           d (if pub
                               (document/fetch-by-major-in-publication pub type n mj lang)
                               (document/fetch-by-major type n mj lang))]
                       (if d
                         {:status 200
                          :body d}
                         (not-found :name n :major mj))))
        by-id-h (fn [req]
                  (if-let [d (document/fetch (path-id req))]
                    {:status 200
                     :body d}
                    (not-found :id (path-id req))))
        edit-h (fn [req]
                 (if-let [u (uid req)]
                   (if-let [d (document/edit (path-id req) u (body req) (pub-id req))]
                     {:status 201
                      :body d}
                     (not-found :id (path-id req)))
                   unauthorized))
        translate-h (fn [req]
                      (if-let [u (uid req)]
                        (if-let [d (document/translate (path-id req)
                                                       (:lang (body req))
                                                       u
                                                       (body req)
                                                       (pub-id req))]
                          {:status 201
                           :body d}
                          (not-found :id (path-id req)))
                        unauthorized))
        publish-h (fn [req]
                    (if-let [u (uid req)]
                      (let [r (document/publish! (path-id req) u)]
                        (cond
                          (= r :forbidden) {:status 403
                                            :body {:error "only the owner may publish"}}
                          (nil? r) (not-found :id (path-id req))
                          (:unpublished-inputs r) {:status 422
                                                   :body {:error "inputs must be published first"
                                                          :unpublished-inputs (:unpublished-inputs
                                                                               r)}}
                          :else {:status 200
                                 :body r}))
                      unauthorized))
        add-input-h (fn [req]
                      (if-let [u (uid req)]
                        (let [r (document/add-input (path-id req) u (body req) (pub-id req))]
                          (cond
                            (= r :input-limit) {:status 422
                                                :body {:error
                                                       (str "at most " di/max-inputs " inputs")}}
                            r {:status 200
                               :body r}
                            :else (not-found :id (path-id req))))
                        unauthorized))
        drop-input-h (fn [req]
                       (if-let [u (uid req)]
                         (if-let [d (document/drop-input (path-id req) u (body req) (pub-id req))]
                           {:status 200
                            :body d}
                           (not-found :id (path-id req)))
                         unauthorized))]
    [prefix {:coercion coercion
             :muuntaja m/instance
             :swagger {:tags #{:agora}}
             :middleware mw}
     [""
      {:get {:handler list-h
             :operationId (str "agora-list-" type)
             :parameters {:query [:map
                                  [:q {:optional true}
                                   [:maybe [:string {:max 200}]]]
                                  [:lang {:optional true}
                                   lang-schema]
                                  [:drafts {:optional true}
                                   [:maybe [:string {:max 8}]]]]}
             :summary (str "List/search " type "s")}
       :post {:handler create-h
              :middleware throttled
              :operationId (str "agora-create-" type)
              :parameters {:body create-body}
              :summary (str "Create a " type)}}]
     ["/by/:name/:major"
      {:get {:handler by-major-h
             :operationId (str "agora-" type "-by-major")
             :parameters {:path [:map [:name name-schema] [:major :int]]
                          :query [:map
                                  [:lang {:optional true}
                                   lang-schema]
                                  [:publication {:optional true}
                                   publication-id-schema]]}
             :summary (str "Latest minor of a " type " by (name, major)")}}]
     ["/:id"
      {:get {:handler by-id-h
             :operationId (str "agora-" type "-by-id")
             :parameters {:path [:map [:id :string]]}
             :summary (str "A " type " by id")}}]
     ["/:id/edit"
      {:post {:handler edit-h
              :middleware throttled
              :operationId (str "agora-edit-" type)
              :parameters {:path [:map [:id :string]]
                           :body edit-body}
              :summary (str "Edit a " type " → new minor")}}]
     ["/:id/translate"
      {:post {:handler translate-h
              :middleware throttled
              :operationId (str "agora-translate-" type)
              :parameters {:path [:map [:id :string]]
                           :body translate-body}
              :summary (str "Create a language version of a " type)}}]
     ["/:id/publish"
      {:post {:handler publish-h
              :middleware throttled
              :operationId (str "agora-publish-" type)
              :parameters {:path [:map [:id :string]]}
              :summary (str "Publish a draft " type " (owner only; prunes intermediate drafts)")}}]
     ["/:id/inputs"
      {:post {:handler add-input-h
              :middleware throttled
              :operationId (str "agora-add-input-" type)
              :parameters {:path [:map [:id :string]]
                           :body input-ref-schema}
              :summary "Add an input link"}
       :delete {:handler drop-input-h
                :middleware throttled
                :operationId (str "agora-drop-input-" type)
                :parameters {:path [:map [:id :string]]
                             :body input-ref-schema}
                :summary "Drop an input link"}}]]))

;; ---------------------------------------------------------------------------
;; Machine-translation suggestion (stateless authoring aid — not a document op)
;; ---------------------------------------------------------------------------

(def ^:private suggest-handler
  (fn [req]
    (if (uid req)
      (let [{:keys [text source target]} (get-in req [:parameters :body])]
        {:status 200
         :body {:translation (or (translate/suggest text source target) text)}})
      unauthorized)))

(defn translate-suggest-route
  [prefix]
  [prefix
   {:post {:coercion coercion
           :handler suggest-handler
           :muuntaja m/instance
           :operationId "agora-translate-suggest"
           :parameters
           {:body [:map [:text [:string {:max 10000}]] [:source lang-schema] [:target lang-schema]]}
           :summary "Machine-translation suggestion (best effort)"
           :swagger {:tags #{:agora}}
           :middleware mw}}])
