(ns landing.agora.endpoints.admin
  "Fresh-start stub. Routes preserved as the rebuild checklist; every handler returns `{}` until
  reimplemented on the New Wire.")

(defn- ok
  [_]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body "{}"})

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
    {:post {:handler ok
            :operationId "agora-admin-rebuild"
            :summary "Recompute derived caches now (the successor index)"}}]])
