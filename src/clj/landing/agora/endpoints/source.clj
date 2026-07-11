(ns landing.agora.endpoints.source
  "Bibliographic source API. Sources are `type=\"source\"` **documents** (see
  landing.agora.source), so create/edit go through the generic document engine (owner = the
  cited author person); search/recent read them back as flat source objects for the picker.
  Search is anonymous; create + edit + recent need a logged-in user."
  (:require
   [landing.agora.document            :as document]
   [landing.agora.document-store      :as store]
   [landing.agora.endpoints.error     :as error]
   [landing.agora.endpoints.throttle  :as throttle]
   [landing.agora.source              :as source]
   [landing.language                  :as language]
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

(def ^:private search-handler
  (fn [req]
    {:status 200
     :body (source/search (get-in req [:parameters :query]))}))

(def ^:private recent-handler
  (fn [req]
    (if (uid req)
      {:status 200
       :body (source/list-recent)}
      unauthorized)))

(defn- source-content
  [{:keys [title year editor]}]
  {:kind "source"
   :title title
   :year year
   :editor editor})

(def ^:private create-handler
  ;; a source's **owner is its cited author** (`:person-id`) — usually a login-less external
  ;; person — so the byline and attribution ("Sun Tzŭ believes that …") resolve through it.
  (fn [req]
    (if (uid req)
      (let [{:keys [person-id]
             :as body} (get-in req [:parameters :body])]
        {:status 201
         :body (source/present-doc (document/create "source" person-id (source-content body)))})
      unauthorized)))

(def ^:private edit-handler
  ;; `:id` is the source **cid**; resolve it to the current row, then edit → new minor.
  (fn [req]
    (if (uid req)
      (let [cid (get-in req [:parameters :path :id])
            {:keys [person-id]
             :as body} (get-in req [:parameters :body])
            id (store/resolve-latest-id source/source-type cid 1 language/default-lang)]
        (if-let [updated (and id (document/edit id person-id (source-content body)))]
          {:status 200
           :body (source/present-doc updated)}
          {:status 404
           :body {:error "source not found"}}))
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
    [:maybe [:string {:max 255}]]]])

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
           :conflicting true
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
           :summary "Search sources by author / title / year"}
     :post {:handler create-handler
            :middleware throttled
            :operationId "agora-source-create"
            :parameters {:body create-body}
            :summary "Create a bibliographic source (a type=source document)"}}]
   ["/recent"
    {:get {:handler recent-handler
           :operationId "agora-source-recent"
           :summary "Recently created sources (one-click reuse)"}}]
   ["/:id"
    {:post {:handler edit-handler
            :middleware throttled
            :operationId "agora-source-edit"
            :parameters {:path [:map [:id :string]]
                         :body create-body}
            :summary "Edit an existing source → new minor"}}]])
