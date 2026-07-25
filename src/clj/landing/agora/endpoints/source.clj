(ns landing.agora.endpoints.source
  "Fresh-start stub. Routes preserved as the rebuild checklist; every handler returns `{}` until
  reimplemented on the New Wire.")

(defn- ok
  [_]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body "{}"})

(defn source-routes
  [prefix]
  [prefix
   [""
    {:get {:handler ok
           :operationId "agora-source-search"
           :summary "Search works by author / title / year"}
     :post {:handler ok
            :operationId "agora-source-create"
            :summary "Create a bibliographic work"}}]
   ["-recent"
    {:get {:handler ok
           :operationId "agora-source-recent"
           :summary "Recently created works (one-click reuse)"}}]
   ["/:id"
    {:post {:handler ok
            :operationId "agora-source-edit"
            :summary "Edit a work (new version)"}}]])
