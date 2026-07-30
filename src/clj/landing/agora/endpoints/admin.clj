(ns landing.agora.endpoints.admin
  "Maintenance and consistency routes over the document store: list lineages, list reference issues,
  drop or compact a lineage, recompute the successor index. Every route is restricted to the
  platform owner (`admin-only`): 401 when anonymous, 403 when logged in but not on the admin
  allowlist."
  (:require
   [landing.agora.admin               :as admin]
   [landing.agora.auth                :as auth]
   [muuntaja.core                     :as m]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(def ^:private mw
  [parameters/parameters-middleware
   muuntaja/format-negotiate-middleware
   muuntaja/format-response-middleware
   muuntaja/format-request-middleware])

(defn- admin-only
  "Run `(f req)` only for an authenticated administrator; 401 anonymous, 403 non-admin."
  [req f]
  (if-let [uid (get-in req [:session :user-id])]
    (if (:admin (auth/get-user uid))
      (f req)
      {:status 403
       :body {:error "admin only"}})
    {:status 401
     :body {:error "login required"}}))

(defn- tnrs
  [req]
  (admin-only req
              (fn [_]
                {:status 200
                 :body (admin/all-tnrs)})))

(defn- issues
  [req]
  (admin-only req
              (fn [_]
                {:status 200
                 :body (admin/consistency-issues)})))

(defn- drop-tnr
  [req]
  (admin-only req
              (fn [{{:keys [type lang major]
                     doc-name :name}
                    :body-params}]
                {:status 200
                 :body {:removed (admin/delete-tnr! type doc-name lang major)}})))

(defn- compact-tnr
  [req]
  (admin-only req
              (fn [{{:keys [type lang major]
                     doc-name :name}
                    :body-params}]
                {:status 200
                 :body {:removed (admin/compact-tnr! type doc-name lang major)}})))

(defn- rebuild
  [req]
  (admin-only req
              (fn [_]
                {:status 200
                 :body (admin/rebuild!)})))

(defn admin-routes
  [prefix]
  [prefix {:muuntaja m/instance
           :middleware mw}
   ["/tnrs"
    {:get {:handler tnrs
           :operationId "agora-admin-tnrs"
           :summary "List document lineages (TNRs) with version counts"}}]
   ["/issues"
    {:get {:handler issues
           :operationId "agora-admin-issues"
           :summary "Versions with reference problems (dangling / self / successor-cache drift)"}}]
   ["/drop-tnr"
    {:post {:handler drop-tnr
            :operationId "agora-admin-drop-tnr"
            :summary "Drop a whole (type, name, lang, major) lineage"}}]
   ["/compact-tnr"
    {:post {:handler compact-tnr
            :operationId "agora-admin-compact-tnr"
            :summary "Keep only the latest minor of a lineage"}}]
   ["/rebuild"
    {:post {:handler rebuild
            :operationId "agora-admin-rebuild"
            :summary "Recompute the successor index"}}]])
