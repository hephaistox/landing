(ns landing.agora.endpoints.publication
  "Publications: open one, list the caller's open ones, fetch by id, rename, and list a publication's
  documents. Opening requires a session; the publication is owned by the caller. Rename and the
  drafts list arrive with the write path."
  (:require
   [landing.agora.auth                :as auth]
   [landing.agora.publication         :as publication]
   [muuntaja.core                     :as m]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(def ^:private mw
  [parameters/parameters-middleware
   muuntaja/format-negotiate-middleware
   muuntaja/format-response-middleware
   muuntaja/format-request-middleware])

(defn- uid [req] (get-in req [:session :user-id]))

(defn- create
  "Open a publication owned by the caller, titled by the request body."
  [req]
  (if-let [id (uid req)]
    {:status 200
     :body
     (publication/create! id (:display-name (auth/get-user id)) (get-in req [:body-params :title]))}
    {:status 401
     :body {:error "login required"}}))

(defn- list-mine
  "The caller's open publications, optionally filtered by `?q=`."
  [req]
  (if-let [id (uid req)]
    {:status 200
     :body (publication/list-open id (get-in req [:query-params "q"]))}
    {:status 401
     :body {:error "login required"}}))

(defn- fetch
  [req]
  (if-let [p (publication/fetch (get-in req [:path-params :id]))]
    {:status 200
     :body p}
    {:status 404
     :body {:error "not found"}}))

(defn- documents
  "The publication's modified documents (drafts). Empty until the write path tags drafts."
  [_req]
  {:status 200
   :body []})

(defn- todo
  "Not wired yet (rename — arrives with the edit write path)."
  [_req]
  {:status 501
   :body {:error "not implemented"}})

(defn publication-routes
  [prefix]
  [prefix {:muuntaja m/instance
           :middleware mw}
   [""
    {:get {:handler list-mine
           :operationId "agora-publications-search"
           :summary "List the caller's open publications"}
     :post {:handler create
            :operationId "agora-publication-create"
            :summary "Open a publication"}}]
   ["/:id"
    {:get {:handler fetch
           :operationId "agora-publication"
           :summary "Fetch a publication by id"}
     :put {:handler todo
           :operationId "agora-publication-rename"
           :summary "Rename a publication"}}]
   ["/:id/documents"
    {:get {:handler documents
           :operationId "agora-publication-documents"
           :summary "The publication's modified documents (drafts)"}}]])
