(ns landing.agora.endpoints.author
  "Fresh-start stub. Routes preserved as the rebuild checklist; every handler returns `{}` until
  reimplemented on the New Wire.")

(defn- ok
  [_]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body "{}"})

(defn author-routes
  [prefix]
  [prefix
   ["/:id"
    {:get {:handler ok
           :operationId "agora-author"
           :summary "Public author profile: card + documents + last activity"}}]])
