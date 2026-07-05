(ns landing.agora.endpoints.admin
  "Agora maintenance API: list KI lineages (TNRs) and prune them. Gated to
  logged-in users; destructive, so intended for the platform owner."
  (:require
   [landing.agora.ki                  :as ki]
   [muuntaja.core                     :as m]
   [reitit.coercion.malli             :refer [coercion]]
   [reitit.ring.coercion              :as rcoercion]
   [reitit.ring.middleware.exception  :as exception]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(defn- user-id [req] (get-in req [:session :user-id]))

(def ^:private unauthorized
  {:status 401
   :body {:error "login required"}})

(def ^:private tnr-ref [:map [:name :string] [:major :int]])

(def list-tnrs-handler
  (fn [req]
    (if (user-id req)
      {:status 200
       :body (ki/list-tnrs)}
      unauthorized)))

(def drop-tnr-handler
  (fn [req]
    (if (user-id req)
      (let [{:keys [name major]} (get-in req [:parameters :body])]
        {:status 200
         :body {:deleted (ki/delete-tnr! name major)}})
      unauthorized)))

(def compact-tnr-handler
  (fn [req]
    (if (user-id req)
      (let [{:keys [name major]} (get-in req [:parameters :body])]
        {:status 200
         :body {:deleted (ki/compact-tnr! name major)}})
      unauthorized)))

(defn admin-routes
  [prefix]
  [prefix {:coercion coercion
           :muuntaja m/instance
           :swagger {:tags #{:agora-admin}}
           :middleware [parameters/parameters-middleware
                        muuntaja/format-negotiate-middleware
                        muuntaja/format-response-middleware
                        exception/exception-middleware
                        muuntaja/format-request-middleware
                        rcoercion/coerce-request-middleware]}
   ["/tnrs"
    {:get {:handler list-tnrs-handler
           :operationId "agora-admin-tnrs"
           :summary "List KI lineages (TNRs) with counts"}}]
   ["/drop-tnr"
    {:post {:handler drop-tnr-handler
            :operationId "agora-admin-drop-tnr"
            :parameters {:body tnr-ref}
            :summary "Drop a whole (name, major) lineage"}}]
   ["/compact-tnr"
    {:post {:handler compact-tnr-handler
            :operationId "agora-admin-compact-tnr"
            :parameters {:body tnr-ref}
            :summary "Keep only the latest minor per language of a lineage"}}]])
