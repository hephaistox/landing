(ns landing.pages.js-example-be
  (:require
   [auto-web.page.builder      :refer [css-meta-preloaded js-lang js-script-link]]
   [hiccup2.core               :refer [html]]
   [landing.pages.js-example   :refer [body]]
   [landing.pages.structure    :refer [links]]
   [landing.pages.structure-be :refer [public-page-header]]))

(defn- render
  [el]
  (str "<!DOCTYPE html>\n"
       (html {:mode :html
              :escape-strings? false}
             el)))

(def dic
  {:js-example {:fr "Exemple de jobshop"
                :en "Jobshop example"}
   :js-example-desc {:fr "Exemple de jobshop"
                     :en "Jobshop example"}})

(defn js-example-page
  [http-request]
  (let [l (:lang http-request)
        tr #(get-in dic [% l])]
    [:html {:lang (:lang http-request)}
     (vec (concat [:head]
                  (public-page-header http-request (tr :js-example) (tr :js-example-desc))
                  [(js-lang l)]
                  [(css-meta-preloaded (:workshop links))]))
     (body http-request)
     (js-script-link (:js-example links))]))

(defn js-example-response
  [http-request]
  {:status 200
   :headers {"content-type" "text/html"
             "Access-Control-Allow-Origin" "*"}
   :body (render (js-example-page http-request))})
