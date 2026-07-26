(ns landing.agora.endpoints.people
  "People: search, and create a login-less external (cited) person.")

(defn- ok
  [_]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body "{}"})

(defn people-routes
  [prefix]
  [prefix
   [""
    {:get {:handler ok
           :operationId "agora-people-search"
           :summary "Search people"}
     :post {:handler ok
            :operationId "agora-people-create"
            :summary "Create a login-less external person"}}]])
