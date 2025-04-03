(ns landing.pages.article-be
  (:require
   [hiccup2.core               :refer [html]]
   [landing.article            :refer [article-map]]
   [landing.pages.article      :refer [body]]
   [landing.pages.home-be      :refer [home-response]]
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
     (vec (concat [:head] (public-page-header http-request (tr article-title) (tr article-desc))))
     (body http-request article-body)]))

(defn article-response
  [http-request]
  (let [{:keys [handler title description]}
        (some-> (get-in http-request [:reitit.core/match :path-params :article-id])
                article-map)]
    (if article-map
      {:status 200
       :headers {"content-type" "text/html"
                 "charset" "UTF-8"}
       :body (render (article-page http-request title description handler))}
      (home-response home-response))))
