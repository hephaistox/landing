(ns landing.agora.endpoints.author
  "Public author profile: a person's card, their documents, and last activity.")

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
