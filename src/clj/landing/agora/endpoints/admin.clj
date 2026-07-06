(ns landing.agora.endpoints.admin
  "Agora maintenance API: list KI lineages (TNRs) and prune them. Destructive, so
  restricted to the platform owner (see landing.agora.auth/admin-emails), not just
  any logged-in user."
  (:require
   [landing.agora.auth                :as auth]
   [landing.agora.endpoints.error     :as error]
   [landing.agora.ki                  :as ki]
   [muuntaja.core                     :as m]
   [reitit.coercion.malli             :refer [coercion]]
   [reitit.ring.coercion              :as rcoercion]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(def ^:private unauthorized
  {:status 401
   :body {:error "login required"}})

(def ^:private forbidden
  {:status 403
   :body {:error "admin only"}})

(defn- admin-guard
  "Run `f` only for an authenticated administrator: 401 when anonymous, 403 when
  logged in but not on the admin allowlist."
  [req f]
  (if-let [uid (get-in req [:session :user-id])]
    (if (:admin (auth/get-user uid)) (f) forbidden)
    unauthorized))

(def ^:private tnr-ref [:map [:name :string] [:major :int]])

(def list-tnrs-handler
  (fn [req]
    (admin-guard req
                 (fn []
                   {:status 200
                    :body (ki/list-tnrs)}))))

(def drop-tnr-handler
  (fn [req]
    (admin-guard req
                 (fn []
                   (let [{:keys [name major]} (get-in req [:parameters :body])]
                     {:status 200
                      :body {:deleted (ki/delete-tnr! name major)}})))))

(def compact-tnr-handler
  (fn [req]
    (admin-guard req
                 (fn []
                   (let [{:keys [name major]} (get-in req [:parameters :body])]
                     {:status 200
                      :body {:deleted (ki/compact-tnr! name major)}})))))

(defn admin-routes
  [prefix]
  [prefix {:coercion coercion
           :muuntaja m/instance
           :swagger {:tags #{:agora-admin}}
           :middleware [parameters/parameters-middleware
                        muuntaja/format-negotiate-middleware
                        muuntaja/format-response-middleware
                        error/exception-middleware
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
