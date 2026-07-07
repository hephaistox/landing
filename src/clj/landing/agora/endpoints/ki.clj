(ns landing.agora.endpoints.ki
  "HTTP route for reading a single Knowledge Item: GET /api/ki/:id.

  No auth, no pagination. Content negotiation (JSON/EDN) via muuntaja. The :id
  path parameter is declared via malli coercion so it is documented in Swagger
  (input box) rather than called as a literal {id}."
  (:require
   [landing.agora.domain              :as domain]
   [landing.agora.endpoints.error     :as error]
   [landing.agora.endpoints.throttle  :as throttle]
   [landing.agora.ki                  :as ki]
   [landing.agora.translate           :as translate]
   [landing.language                  :as language]
   [muuntaja.core                     :as m]
   [reitit.coercion.malli             :refer [coercion]]
   [reitit.ring.coercion              :as rcoercion]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(def ^:private kind-enum
  "Malli enum of the epistemic `kind`s, from the canonical domain set
  (landing.agora.domain/kinds). The API represents it as a string, so the keyword set
  is mapped to strings."
  (into [:enum] (map name domain/kind-ids)))

;; Size-bounded field schemas — cap authored text so a single request can't store
;; an unbounded blob (storage/DoS abuse) or smuggle an oversized slug into URLs.
(def ^:private name-schema
  [:string {:min 1
            :max 200}])
(def ^:private title-schema
  [:string {:max 200}])
(def ^:private statement-schema
  [:string {:min 1
            :max 10000}])
(def ^:private lang-schema
  [:string {:max 8}])

(def ^:private input-ref-schema
  "Body for add/drop input: a Major-level KI reference (name + major; identity's
  T is the object type `ki`)."
  [:map [:name name-schema] [:major :int]])

(defn- user-id
  "The logged-in user's id from the session, or nil. Authoring requires it (#38)."
  [req]
  (get-in req [:session :user-id]))

(def ^:private unauthorized
  {:status 401
   :body {:error "login required"}})

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
  (type + output statement), owned by the logged-in user. 201 with the new KI,
  404 if the source is unknown, 401 if not logged in (#38)."
  (fn [req]
    (if-let [uid (user-id req)]
      (let [id (get-in req [:parameters :path :id])
            edit (get-in req [:parameters :body])]
        (if-let [ki (ki/edit-ki id uid edit)]
          {:status 201
           :body ki}
          {:status 404
           :body {:error "KI not found"
                  :id id}}))
      unauthorized)))

(def translate-ki-handler
  "Create a language version of the KI :id and its inputs. Body: target `:lang` and
  the author-validated `:statement` (edited translation). Owned by the logged-in
  user. 201 with the new version, 404 if the source is unknown, 401 if not logged
  in."
  (fn [req]
    (if-let [uid (user-id req)]
      (let [id (get-in req [:parameters :path :id])
            {to-lang :lang
             title :title
             statement :statement}
            (get-in req [:parameters :body])]
        (if-let [ki (ki/translate-ki id to-lang uid title statement)]
          {:status 201
           :body ki}
          {:status 404
           :body {:error "KI not found"
                  :id id}}))
      unauthorized)))

(def translate-suggest-handler
  "Best-effort machine-translation *suggestion* for the authoring UI (logged-in
  users only). Body: {:text :source :target}. Always 200 with {:translation …},
  falling back to the source text when the external service is unavailable."
  (fn [req]
    (if (user-id req)
      (let [{:keys [text source target]} (get-in req [:parameters :body])]
        {:status 200
         :body {:translation (or (translate/suggest text source target) text)}})
      unauthorized)))

(def search-ki-handler
  "With ?q= present, search KIs by name; otherwise return the discoverability
  list (recent KIs, latest minor). Both are scoped to the content language `?lang=`
  (defaults to fr), so a French page only sees French KIs."
  (fn [req]
    (let [q (get-in req [:parameters :query :q])
          lang (or (get-in req [:parameters :query :lang]) language/default-lang)]
      {:status 200
       :body (if (seq q) (ki/search-kis q lang) (ki/list-kis lang))})))

(def create-ki-handler
  "Create a new KI (major 1, minor 0) owned by the logged-in user. 201 with the
  KI, or 401 if not logged in (#38)."
  (fn [req]
    (if-let [uid (user-id req)]
      {:status 201
       :body (ki/create-ki uid (get-in req [:parameters :body]))}
      unauthorized)))

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
                 error/exception-middleware
                 muuntaja/format-request-middleware
                 rcoercion/coerce-request-middleware]
    :get {:handler search-ki-handler
          :operationId "agora-search-kis"
          :parameters {:query [:map
                               [:q {:optional true}
                                [:string {:max 200}]]
                               [:lang {:optional true}
                                lang-schema]]}
          :summary "Search KIs by name (scoped to ?lang=)"}
    :post {:handler create-ki-handler
           :middleware [(:middleware-fn throttle/authoring-rate-limiter)]
           :operationId "agora-create-ki"
           :parameters {:body [:map
                               [:name name-schema]
                               [:title {:optional true}
                                title-schema]
                               [:kind kind-enum]
                               [:lang {:optional true}
                                lang-schema]
                               [:output-statement statement-schema]]}
           :summary "Create a new KI (major 1, minor 0)"}}])

(def add-input-handler
  "Declare an input on the KI :id (logged-in users only) → a new minor version. 200
  with the new KI, 404 if unknown, 422 if the input cap is reached, 401 if not
  logged in."
  (fn [req]
    (if-let [uid (user-id req)]
      (let [id (get-in req [:parameters :path :id])
            input (get-in req [:parameters :body])
            result (ki/add-input id uid input)]
        (cond
          (= result :input-limit)
          {:status 422
           :body {:error (str "a KI may declare at most " domain/max-inputs " inputs")}}
          result {:status 200
                  :body result}
          :else {:status 404
                 :body {:error "KI not found"
                        :id id}}))
      unauthorized)))

(def drop-input-handler
  "Remove a declared input from the KI :id (logged-in users only) → a new minor version.
  200 with the new KI, 404 if unknown, 401 if not logged in."
  (fn [req]
    (if-let [uid (user-id req)]
      (let [id (get-in req [:parameters :path :id])
            input (get-in req [:parameters :body])]
        (if-let [ki (ki/drop-input id uid input)]
          {:status 200
           :body ki}
          {:status 404
           :body {:error "KI not found"
                  :id id}}))
      unauthorized)))

(defn inputs-route
  "Manage a KI's input links: POST adds one, DELETE removes one. Body is a
  Major-level KI reference {:name :major}."
  [prefix]
  [prefix
   {;; :conflicting — this 5-segment API path overlaps the `/agora/:lang/ki/…`
    ;; page route for the conflict detector; reitit's matcher prefers the literal
    ;; `api` segment, so requests resolve to the API handler as intended.
    :conflicting true
    :coercion coercion
    :muuntaja m/instance
    :swagger {:tags #{:agora}}
    ;; both methods mutate (each = a new minor), so throttle at the route level.
    :middleware [parameters/parameters-middleware
                 muuntaja/format-negotiate-middleware
                 muuntaja/format-response-middleware
                 error/exception-middleware
                 muuntaja/format-request-middleware
                 rcoercion/coerce-request-middleware
                 (:middleware-fn throttle/authoring-rate-limiter)]
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
  "Return the latest-minor KI of a (name, major) lineage in language `?lang=`
  (defaults to fr) — the permanent public identity — or 404. Falls back to another
  language when the concept is not yet translated."
  (fn [req]
    (let [{ki-name :name
           ki-major :major}
          (get-in req [:parameters :path])
          lang (or (get-in req [:parameters :query :lang]) language/default-lang)]
      (if-let [ki (ki/fetch-ki-by-major ki-name ki-major lang)]
        {:status 200
         :body ki}
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
                 :parameters {:path [:map [:name name-schema] [:major :int]]
                              :query [:map
                                      [:lang {:optional true}
                                       lang-schema]]}
                 :summary "Fetch a KI's latest minor by (name, major), in ?lang="
                 :swagger {:tags #{:agora}}
                 :middleware [parameters/parameters-middleware
                              muuntaja/format-negotiate-middleware
                              muuntaja/format-response-middleware
                              error/exception-middleware
                              muuntaja/format-request-middleware
                              rcoercion/coerce-request-middleware]}}])

(defn edit-ki-route
  [prefix]
  [prefix
   {;; :conflicting — see inputs-route: 5-segment API path overlaps the page route
    ;; `/agora/:lang/ki/:name/:major` for the detector; the matcher resolves it.
    :conflicting true
    :post {:coercion coercion
           :handler edit-ki-handler
           :muuntaja m/instance
           :operationId "agora-edit-ki"
           :parameters {:path [:map [:id :string]]
                        :body [:map
                               [:title {:optional true}
                                title-schema]
                               [:kind kind-enum]
                               [:output-statement statement-schema]]}
           :summary "Edit a KI — produces a new minor version (immutable)"
           :swagger {:tags #{:agora}}
           :middleware [;; query-params & form-params
                        parameters/parameters-middleware
                        ;; content-negotiation
                        muuntaja/format-negotiate-middleware
                        ;; encoding response body
                        muuntaja/format-response-middleware
                        ;; exception handling
                        error/exception-middleware
                        ;; decoding request body
                        muuntaja/format-request-middleware
                        ;; coercing request parameters (path :id + body)
                        rcoercion/coerce-request-middleware
                        ;; throttle authoring writes
                        (:middleware-fn throttle/authoring-rate-limiter)]}}])

(defn translate-ki-route
  [prefix]
  [prefix
   {;; :conflicting — see inputs-route: 5-segment API path overlaps the page route.
    :conflicting true
    :post {:coercion coercion
           :handler translate-ki-handler
           :muuntaja m/instance
           :operationId "agora-translate-ki"
           :parameters {:path [:map [:id :string]]
                        :body [:map
                               [:lang lang-schema]
                               [:title {:optional true}
                                title-schema]
                               [:statement {:optional true}
                                statement-schema]]}
           :summary "Create a language version of a KI and its inputs"
           :swagger {:tags #{:agora}}
           :middleware [parameters/parameters-middleware
                        muuntaja/format-negotiate-middleware
                        muuntaja/format-response-middleware
                        error/exception-middleware
                        muuntaja/format-request-middleware
                        rcoercion/coerce-request-middleware
                        (:middleware-fn throttle/authoring-rate-limiter)]}}])

(defn translate-suggest-route
  [prefix]
  [prefix
   {:post {:coercion coercion
           :handler translate-suggest-handler
           :muuntaja m/instance
           :operationId "agora-translate-suggest"
           :parameters
           {:body [:map [:text [:string {:max 10000}]] [:source lang-schema] [:target lang-schema]]}
           :summary "Machine-translation suggestion (best effort)"
           :swagger {:tags #{:agora}}
           :middleware [parameters/parameters-middleware
                        muuntaja/format-negotiate-middleware
                        muuntaja/format-response-middleware
                        error/exception-middleware
                        muuntaja/format-request-middleware
                        rcoercion/coerce-request-middleware]}}])

(defn ki-route
  [prefix]
  [prefix
   {;; :conflicting — `/agora/api/ki/:id` overlaps the app-shell `/agora/:lang/ki/:id`
    ;; for the detector; reitit's matcher prefers the literal `api` segment.
    :conflicting true
    :get {:coercion coercion
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
                       error/exception-middleware
                       ;; decoding request body
                       muuntaja/format-request-middleware
                       ;; coercing request parameters (path :id)
                       rcoercion/coerce-request-middleware]}}])
