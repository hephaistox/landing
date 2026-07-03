(ns landing.agora.endpoints.article
  "HTTP route for reading a single article: GET /api/article/:id.

  No auth. Content negotiation (JSON/EDN) via muuntaja; the :id path parameter is
  declared via malli coercion so it is documented in Swagger. Mirrors
  landing.agora.endpoints.ki."
  (:require
   [landing.agora.article             :as article]
   [muuntaja.core                     :as m]
   [reitit.coercion.malli             :refer [coercion]]
   [reitit.ring.coercion              :as rcoercion]
   [reitit.ring.middleware.exception  :as exception]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(def article-handler
  "Return the article identified by the :id path param, or 404 if none."
  (fn [req]
    (let [id (get-in req [:parameters :path :id])]
      (if-let [a (article/fetch-article id)]
        {:status 200
         :body a}
        {:status 404
         :body {:error "Article not found"
                :id id}}))))

(defn article-route
  [prefix]
  [prefix
   {:get {:coercion coercion
          :handler article-handler
          :muuntaja m/instance
          :operationId "agora-article-by-id"
          :parameters {:path [:map [:id :string]]}
          :summary "Fetch a single article by id"
          :swagger {:tags #{:agora}}
          :middleware [;; query-params & form-params
                       parameters/parameters-middleware
                       ;; content-negotiation
                       muuntaja/format-negotiate-middleware
                       ;; encoding response body
                       muuntaja/format-response-middleware
                       ;; exception handling
                       exception/exception-middleware
                       ;; decoding request body
                       muuntaja/format-request-middleware
                       ;; coercing request parameters (path :id)
                       rcoercion/coerce-request-middleware]}}])
