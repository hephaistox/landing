(ns landing.agora.endpoints.people
  "People: search existing people (accounts + external cited authors) for the author picker, and
  create a login-less external person for a citation whose author isn't a platform member. Both are
  backed by `auth`; creating requires a session."
  (:require
   [clojure.string                    :as str]
   [landing.agora.auth                :as auth]
   [muuntaja.core                     :as m]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(def ^:private mw
  [parameters/parameters-middleware
   muuntaja/format-negotiate-middleware
   muuntaja/format-response-middleware
   muuntaja/format-request-middleware])

(defn- search
  [req]
  {:status 200
   :body (auth/search-people (get-in req [:query-params "q"]))})

(defn- create
  [req]
  (if (get-in req [:session :user-id])
    (let [nm (get-in req [:body-params :display-name])]
      (if (str/blank? nm)
        {:status 400
         :body {:error "blank name"}}
        {:status 200
         :body (auth/create-external-person! nm)}))
    {:status 401
     :body {:error "login required"}}))

(defn people-routes
  [prefix]
  [prefix {:muuntaja m/instance
           :middleware mw}
   [""
    {:get {:handler search
           :operationId "agora-people-search"
           :summary "Search people (accounts + external cited authors)"}
     :post {:handler create
            :operationId "agora-people-create"
            :summary "Create a login-less external person"}}]])
