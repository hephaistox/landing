(ns landing.agora.endpoints.source
  "Bibliographic **source** (work) API. A work is an ordinary `type=source` document — created and
  edited through the **generic document engine**, exactly like a KI; only its `content` is
  bibliographic (title/year/editor/url + the cited author). Search / recent / resolve read it via
  the New Wire `db.source`. A `kind=source` citation references a work by its id. Search is
  anonymous; create + edit + recent need a logged-in user."
  (:require
   [clojure.string                    :as str]
   [landing.agora.db.source           :as db-source]
   [landing.agora.document-old        :as document]
   [landing.agora.endpoints.error     :as error]
   [landing.agora.endpoints.throttle  :as throttle]
   [landing.agora.store-old           :as store]
   [muuntaja.core                     :as m]
   [reitit.coercion.malli             :refer [coercion]]
   [reitit.ring.coercion              :as rcoercion]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(def ^:private throttled [(:middleware-fn throttle/authoring-rate-limiter)])
(defn- uid [req] (get-in req [:session :user-id]))
(def ^:private unauthorized
  {:status 401
   :body {:error "login required"}})

(defn- work-content
  "A work's authored `content`: bibliographic fields + the cited author, denormalized from
  `person-id`. A leaf — no `:text`, so the generic engine derives no inputs."
  [{:keys [person-id title year editor url]}]
  {:title (str/trim (or title ""))
   :year year
   :editor (some-> editor
                   str/trim
                   not-empty)
   :url (some-> url
                str/trim
                not-empty)
   :author-id person-id
   :author-name (store/author-name person-id)})

(defn- resolved
  "The resolved work (`{:source-id …}`) for a create/edit view (or nil)."
  [view]
  (some->> view
           :id
           (hash-map :source-id)
           db-source/resolve-ref))

(def ^:private search-handler
  (fn [req]
    {:status 200
     :body (db-source/search (get-in req [:parameters :query]))}))

(def ^:private recent-handler
  (fn [req]
    (if-let [u (uid req)]
      {:status 200
       :body (db-source/list-recent u)}
      unauthorized)))

(defn- pub-id [req] (get-in req [:parameters :body :publication-id]))

(def ^:private create-handler
  (fn [req]
    (if-let [u (uid req)]
      {:status 201
       :body
       (resolved
        (document/create :source u (work-content (get-in req [:parameters :body])) (pub-id req)))}
      unauthorized)))

(def ^:private edit-handler
  (fn [req]
    (if-let [u (uid req)]
      (if-let [r (resolved (document/edit (get-in req [:parameters :path :id])
                                          u
                                          (work-content (get-in req [:parameters :body]))
                                          (pub-id req)))]
        {:status 200
         :body r}
        {:status 404
         :body {:error "source not found"}})
      unauthorized)))

(def ^:private create-body
  [:map
   [:person-id
    [:string {:min 1
              :max 64}]]
   [:title
    [:string {:min 1
              :max 512}]]
   [:year {:optional true}
    [:maybe :int]]
   [:editor {:optional true}
    [:maybe [:string {:max 255}]]]
   [:url {:optional true}
    [:maybe [:string {:max 1000}]]]
   [:publication-id {:optional true}
    [:maybe [:string {:max 64}]]]])

(def ^:private search-query
  [:map
   [:author {:optional true}
    [:maybe [:string {:max 200}]]]
   [:title {:optional true}
    [:maybe [:string {:max 200}]]]
   [:year {:optional true}
    [:maybe :int]]])

(defn source-routes
  [prefix]
  [prefix {:coercion coercion
           :muuntaja m/instance
           :swagger {:tags #{:agora}}
           :middleware [parameters/parameters-middleware
                        muuntaja/format-negotiate-middleware
                        muuntaja/format-response-middleware
                        error/exception-middleware
                        muuntaja/format-request-middleware
                        rcoercion/coerce-request-middleware]}
   [""
    {:get {:handler search-handler
           :operationId "agora-source-search"
           :parameters {:query search-query}
           :summary "Search works by author / title / year"}
     :post {:handler create-handler
            :middleware throttled
            :operationId "agora-source-create"
            :parameters {:body create-body}
            :summary "Create a bibliographic work"}}]
   ["-recent"
    {:get {:handler recent-handler
           :operationId "agora-source-recent"
           :summary "Recently created works (one-click reuse)"}}]
   ["/:id"
    {:post {:handler edit-handler
            :middleware throttled
            :operationId "agora-source-edit"
            :parameters {:path [:map [:id :string]]
                         :body create-body}
            :summary "Edit a work (new version)"}}]])
