(ns landing.agora.endpoints.admin
  "Maintenance and consistency routes over the document store: list lineages, list reference issues,
  drop or compact a lineage, recompute the successor index."
  (:require
   [landing.agora.db.document :as db-doc]))

(defn- ok
  [_]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body "{}"})

(defn- rebuild
  "Recompute the successor index."
  [_]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (str "{\"lineages\":" (db-doc/rebuild-successor-index!) "}")})

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
