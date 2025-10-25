(ns landing.endpoints.html.article-be
  (:require
   [auto-web.page.builder                  :refer [print-css-meta]]
   [landing.article                        :refer [article-map]]
   [landing.endpoints.html                 :refer [html-middlewares]]
   [landing.endpoints.html.error-be        :refer [page-not-found-response]]
   [landing.endpoints.html.public-pages-be :refer [public-page-header]]
   [landing.hiccup                         :refer [render-html]]
   [landing.pages.article                  :refer [body]]
   [landing.pages.structure                :refer [links]]
   [muuntaja.core                          :as m]
   [reitit.coercion.malli                  :refer [coercion]]
   [reitit.ring.coercion                   :as rcoercion]
   [reitit.ring.middleware.exception       :as exception]
   [reitit.ring.middleware.muuntaja        :as muuntaja]
   [reitit.ring.middleware.parameters      :as parameters]
   [ring.middleware.keyword-params         :refer [wrap-keyword-params]]))

(defn article-page
  [http-request article-title article-desc handler]
  (let [l (:lang http-request)
        tr #(get % l)
        article-body (apply handler [http-request l])]
    [:html {:lang (:lang http-request)}
     (vec (concat [:head]
                  (public-page-header http-request (tr article-title) (tr article-desc))
                  [(print-css-meta (:print-css links))]))
     (body http-request article-body)]))

(defn article-response
  [http-request]
  (let [{:keys [handler title description]
         :as article-response}
        (some-> (get-in http-request [:reitit.core/match :path-params :article-id])
                article-map)]
    (if article-response
      {:status 200
       :headers {"content-type" "text/html"
                 "charset" "UTF-8"}
       :body (render-html (article-page http-request title description handler))}
      (page-not-found-response http-request))))

(def article-middlewares
  "In addition to html common middlewares, the articles needs path parameter coercion"
  (concat html-middlewares
          [muuntaja/format-middleware
           ;; coercing request parameters
           rcoercion/coerce-request-middleware
           ;; coercing response bodys
           rcoercion/coerce-response-middleware]))

(defn article-route
  [prefix]
  [(str prefix "/:article-id")
   {:get {:swagger {:tags #{:html}}
          :handler article-response
          :parameters {:path [:map [:article-id :string]]}
          :muuntaja m/instance
          :coercion coercion
          :middleware (concat article-middlewares
                              [;; query-params & form-params
                               parameters/parameters-middleware
                               ;; content-negotiation
                               muuntaja/format-negotiate-middleware
                               ;; encoding response body
                               muuntaja/format-response-middleware
                               ;; exception handling
                               exception/exception-middleware
                               ;; decoding request body
                               muuntaja/format-request-middleware
                               ;; coercing response bodys
                               rcoercion/coerce-response-middleware
                               ;; coercing request parameters
                               rcoercion/coerce-request-middleware
                               wrap-keyword-params])
          :summary "An article in the website blog"}}])
