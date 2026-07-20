(ns landing.agora.endpoints.publication
  "Publication API: open a publication, fetch one by id, and list the caller's open
  publications. A publication is owned by its creator, so create and list are auth-gated;
  fetch-by-id is public (a publication is not secret, just a work-package)."
  (:require
   [landing.agora.document-old            :as document]
   [landing.agora.endpoints.error     :as error]
   [landing.agora.endpoints.throttle  :as throttle]
   [landing.agora.publication         :as publication]
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

(def ^:private list-handler
  (fn [req]
    (if-let [u (uid req)]
      {:status 200
       ;; `?q=` searches publications by title (caller's own first); blank → the caller's own
       :body (publication/search (get-in req [:parameters :query :q]) u)}
      unauthorized)))

(def ^:private create-handler
  (fn [req]
    (if-let [u (uid req)]
      {:status 201
       :body (publication/create! u
                                  (get-in req [:parameters :body :title])
                                  (get-in req [:parameters :body :lang]))}
      unauthorized)))

(def ^:private fetch-handler
  (fn [req]
    (if-let [d (publication/fetch (get-in req [:parameters :path :id]))]
      {:status 200
       :body d}
      {:status 404
       :body {:error "not found"}})))

(def ^:private rename-handler
  (fn [req]
    (if-let [u (uid req)]
      (if-let [p (publication/rename! (get-in req [:parameters :path :id])
                                      u
                                      (get-in req [:parameters :body :title]))]
        {:status 200
         :body p}
        {:status 404
         :body {:error "not found or not owned"}})
      unauthorized)))

(def ^:private documents-handler
  (fn [req]
    {:status 200
     :body (document/cards-in-publication (get-in req [:parameters :path :id]))}))

(defn publication-routes
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
    {:get {:handler list-handler
           :operationId "agora-publications-search"
           :parameters {:query [:map
                                [:q {:optional true}
                                 [:maybe [:string {:max 200}]]]
                                [:mine {:optional true}
                                 [:maybe [:string {:max 4}]]]]}
           :summary "Search publications by title (caller's own first); blank → the caller's own"}
     :post {:handler create-handler
            :middleware throttled
            :operationId "agora-publication-create"
            :parameters {:body [:map
                                [:title
                                 [:string {:min 1
                                           :max 200}]]
                                [:lang {:optional true}
                                 [:maybe [:string {:max 8}]]]]}
            :summary "Open a new publication"}}]
   ["/:id"
    {:get {:handler fetch-handler
           :operationId "agora-publication"
           :parameters {:path [:map [:id [:string {:max 64}]]]}
           :summary "A publication by its cid"}
     :put {:handler rename-handler
           :middleware throttled
           :operationId "agora-publication-rename"
           :parameters {:path [:map [:id [:string {:max 64}]]]
                        :body [:map
                               [:title
                                [:string {:min 1
                                          :max 200}]]]}
           :summary "Rename a publication → new minor (owner only)"}}]
   ["/:id/documents"
    {:get {:handler documents-handler
           :operationId "agora-publication-documents"
           :parameters {:path [:map [:id [:string {:max 64}]]]}
           :summary "The publication's modified set (distinct lineages, latest tagged minor)"}}]])
