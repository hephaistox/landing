(ns landing.agora.endpoints.admin
  "Agora maintenance API: list KI lineages (TNRs) and prune them. Destructive, so
  restricted to the platform owner (see landing.agora.auth/admin-emails), not just
  any logged-in user."
  (:require
   [landing.agora.auth                :as auth]
   [landing.agora.document            :as document]
   [landing.agora.endpoints.error     :as error]
   [muuntaja.core                     :as m]
   [reitit.coercion.malli             :refer [coercion]]
   [reitit.ring.coercion              :as rcoercion]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(defn- admin-guard
  "Run `f` only for an authenticated administrator: 401 when anonymous, 403 when
  logged in but not on the admin allowlist."
  [req f]
  (if-let [uid (get-in req [:session :user-id])]
    (if (:admin (auth/get-user uid))
      (f)
      {:status 403
       :body {:error "admin only"}})
    {:status 401
     :body {:error "login required"}}))

(def ^:private tnr-ref [:map [:type :string] [:name :string] [:lang :string] [:major :int]])

(def list-tnrs-handler
  (fn [req]
    (admin-guard req
                 (fn []
                   {:status 200
                    :body (document/all-tnrs)}))))

(def issues-handler
  (fn [req]
    (admin-guard req
                 (fn []
                   {:status 200
                    :body (document/consistency-issues)}))))

(def drop-tnr-handler
  (fn [req]
    (admin-guard req
                 (fn []
                   (let [{:keys [type name lang major]} (get-in req [:parameters :body])]
                     {:status 200
                      :body {:deleted (document/delete-tnr! type name lang major)}})))))

(def compact-tnr-handler
  (fn [req]
    (admin-guard req
                 (fn []
                   (let [{:keys [type name lang major]} (get-in req [:parameters :body])]
                     {:status 200
                      :body {:deleted (document/compact-tnr! type name lang major)}})))))

(def rebuild-handler
  ;; Recompute the derived caches on demand instead of waiting for the daily scheduler.
  (fn [req]
    (admin-guard req
                 (fn []
                   (document/rebuild-successor-index!)
                   {:status 200
                    :body {:rebuilt true}}))))

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
   ["/issues"
    {:get {:handler issues-handler
           :operationId "agora-admin-issues"
           :summary "Nodes with dangling references (broken input / citation)"}}]
   ["/drop-tnr"
    {:post {:handler drop-tnr-handler
            :operationId "agora-admin-drop-tnr"
            :parameters {:body tnr-ref}
            :summary "Drop a whole (name, major) lineage"}}]
   ["/compact-tnr"
    {:post {:handler compact-tnr-handler
            :operationId "agora-admin-compact-tnr"
            :parameters {:body tnr-ref}
            :summary "Keep only the latest minor per language of a lineage"}}]
   ["/rebuild"
    {:post {:handler rebuild-handler
            :operationId "agora-admin-rebuild"
            :summary "Recompute derived caches now (the successor index)"}}]])
