(ns landing.pages.article-be
  (:require
   [landing.pages.article      :refer [body]]
   [landing.pages.structure-be :refer [public-page-header]]))

(defn article-page
  [http-request article-title article-desc handler]
  (let [l (get http-request :lang)
        article-body (apply handler [http-request l])]
    [:html
     (vec (concat [:head] (public-page-header http-request article-title article-desc)))
     (body http-request article-body)]))
