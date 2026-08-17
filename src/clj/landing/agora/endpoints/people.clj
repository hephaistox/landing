(ns landing.agora.endpoints.people
  "People: search existing people (accounts + external cited authors) for the author picker, and
  create a login-less external person for a citation whose author isn't a platform member. Both are
  backed by `auth`; creating requires a session."
  (:require
   [landing.agora.auth                :as auth]
   [landing.agora.endpoints.throttle  :as throttle]
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
   ;; an API error returns a negotiated (JSON) response, not the branded HTML 500
   exception/exception-middleware
   muuntaja/format-request-middleware
   rcoercion/coerce-request-middleware])

(def ^:private error-body
  "The shape of every error response: `{:error \"...\"}`."
  [:map [:error :string]])

(defn- search
  [req]
  {:status 200
   :body (auth/search-people (get-in req [:parameters :query :q]))})

(defn- create
  "Create a login-less external person. `:display-name` is required non-blank (coercion enforces)."
  [req]
  (if (get-in req [:session :user-id])
    {:status 200
     :body (auth/create-external-person! (get-in req [:parameters :body :display-name]))}
    {:status 401
     :body {:error "login required"}}))

(defn people-routes
  [prefix]
  [prefix {:coercion coercion
           :muuntaja m/instance
           :swagger {:tags #{:agora-people}}
           :responses {500 {:description "Unexpected server error"}}
           :middleware mw}
   [""
    {:get {:handler search
           :operationId "agora-people-search"
           :parameters {:query [:map
                                [:q {:optional true}
                                 [:maybe :string]]]}
           :responses {200 {:description "Matching people (accounts + external cited authors)"}}
           :summary "Search people (accounts + external cited authors)"}
     :post {:handler create
            :operationId "agora-people-create"
            :middleware [(:middleware-fn throttle/authoring-rate-limiter)]
            :parameters {:body [:map [:display-name [:string {:min 1}]]]}
            :responses {200 {:description "The created external person"}
                        401 {:description "Login required"
                             :body error-body}
                        429 {:description "Rate limit exceeded"}}
            :summary "Create a login-less external person"}}]])
