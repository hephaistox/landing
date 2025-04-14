(ns landing.pages.article-be
  (:require
   [auto-web.page.builder      :refer [print-css-meta]]
   [hiccup2.core               :refer [html]]
   [landing.article            :refer [article-map]]
   [landing.pages.article      :refer [body]]
   [landing.pages.error-be     :refer [page-not-found-response]]
   [landing.pages.structure    :refer [links]]
   [landing.pages.structure-be :refer [public-page-header]]))

(defn render
  [el]
  (str "<!DOCTYPE html>\n"
       (html {:mode :html
              :escape-strings? false}
             el)))

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
       :body (render (article-page http-request title description handler))}
      (page-not-found-response http-request))))
