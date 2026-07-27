(ns landing.agora.endpoints.admin
  "Maintenance and consistency routes over the document store: list lineages, list reference issues,
  drop or compact a lineage, recompute the successor index. Restricted to the platform owner
  (`auth/admin-emails`)."
  (:require
   [landing.agora.auth        :as auth]
   [landing.agora.db.document :as db-doc]))

(defn- json
  [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body body})

(defn- ok [_] (json 200 "{}"))

(defn- admin-only
  "Call `f` only for an authenticated administrator: 401 when anonymous, 403 when logged in but not
  on the admin allowlist."
  [req f]
  (if-let [uid (get-in req [:session :user-id])]
    (if (:admin (auth/get-user uid)) (f) (json 403 "{\"error\":\"admin only\"}"))
    (json 401 "{\"error\":\"login required\"}")))

(defn- rebuild
  "Recompute the successor index."
  [req]
  (admin-only req #(json 200 (str "{\"lineages\":" (db-doc/rebuild-successor-index!) "}"))))

(defn admin-routes
  [prefix]
  [prefix
   ["/tnrs"
    {:get {:handler ok
           :operationId "agora-admin-tnrs"
           :summary "List KI lineages (TNRs) with counts"}}]
   ["/issues"
    {:get {:handler ok
           :operationId "agora-admin-issues"
           :summary "Nodes with dangling references (broken input / citation)"}}]
   ["/drop-tnr"
    {:post {:handler ok
            :operationId "agora-admin-drop-tnr"
            :summary "Drop a whole (name, major) lineage"}}]
   ["/compact-tnr"
    {:post {:handler ok
            :operationId "agora-admin-compact-tnr"
            :summary "Keep only the latest minor per language of a lineage"}}]
   ["/rebuild"
    {:post {:handler rebuild
            :operationId "agora-admin-rebuild"
            :summary "Recompute derived caches now (the successor index)"}}]])
