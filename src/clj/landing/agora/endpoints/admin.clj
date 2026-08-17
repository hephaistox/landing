(ns landing.agora.endpoints.admin
  "Maintenance and consistency routes over the document store: list lineages, list reference issues,
  drop or compact a lineage, recompute the successor index. Every route is restricted to the
  platform owner (`admin-only`): 401 when anonymous, 403 when logged in but not on the admin
  allowlist."
  (:require
   [landing.agora.admin               :as admin]
   [landing.agora.auth                :as auth]
   [muuntaja.core                     :as m]
   [reitit.coercion.malli             :refer [coercion]]
   [reitit.ring.coercion              :as rcoercion]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(def ^:private mw
  [parameters/parameters-middleware
   muuntaja/format-negotiate-middleware
   muuntaja/format-response-middleware
   muuntaja/format-request-middleware
   rcoercion/coerce-request-middleware])

(def ^:private lineage-body
  "The (type, name, lang, major) lineage a drop/compact targets."
  [:map [:type :string] [:name :string] [:lang :string] [:major :int]])

(def ^:private error-body
  "The shape of every error response: `{:error \"...\"}`."
  [:map [:error :string]])

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
                 :body (admin/reconcile!)})))

(defn admin-routes
  [prefix]
  ;; every admin route is owner-only, so 401/403 (and 500) are declared once here and merge into
  ;; each operation; the routes add only their own 200
  [prefix {:coercion coercion
           :muuntaja m/instance
           :swagger {:tags #{:agora-admin}}
           :responses {401 {:description "Login required"
                            :body error-body}
                       403 {:description "Admin only"
                            :body error-body}
                       500 {:description "Unexpected server error"}}
           :middleware mw}
   ["/tnrs"
    {:get {:handler tnrs
           :operationId "agora-admin-tnrs"
           :responses {200 {:description "Document lineages (TNRs) with version counts"}}
           :summary "List document lineages (TNRs) with version counts"}}]
   ["/issues"
    {:get {:handler issues
           :operationId "agora-admin-issues"
           :responses {200 {:description "Versions with reference problems"}}
           :summary "Versions with reference problems (dangling / self / successor-cache drift)"}}]
   ["/drop-tnr"
    {:post {:handler drop-tnr
            :operationId "agora-admin-drop-tnr"
            :parameters {:body lineage-body}
            :responses {200 {:description "How many versions were removed"}}
            :summary "Drop a whole (type, name, lang, major) lineage"}}]
   ["/compact-tnr"
    {:post {:handler compact-tnr
            :operationId "agora-admin-compact-tnr"
            :parameters {:body lineage-body}
            :responses {200 {:description "How many versions were removed"}}
            :summary "Keep only the latest minor of a lineage"}}]
   ["/rebuild"
    {:post {:handler rebuild
            :operationId "agora-admin-rebuild"
            :responses {200 {:description "Successor index recomputed"}}
            :summary "Recompute the successor index"}}]])
