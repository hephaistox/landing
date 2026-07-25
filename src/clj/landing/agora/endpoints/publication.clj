(ns landing.agora.endpoints.publication
  "Fresh-start stub. Routes preserved as the rebuild checklist; every handler returns `{}` until
  reimplemented on the New Wire.")

(defn- ok
  [_]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body "{}"})

(defn publication-routes
  [prefix]
  [prefix
   [""
    {:get {:handler ok
           :operationId "agora-publications-search"
           :summary "List the caller's open publications"}
     :post {:handler ok
            :operationId "agora-publication-create"
            :summary "Open a publication"}}]
   ["/:id"
    {:get {:handler ok
           :operationId "agora-publication"
           :summary "Fetch a publication by id"}
     :put {:handler ok
           :operationId "agora-publication-rename"
           :summary "Rename a publication"}}]
   ["/:id/documents"
    {:get {:handler ok
           :operationId "agora-publication-documents"
           :summary "The publication's modified documents (drafts)"}}]])
