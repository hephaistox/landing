(ns landing.endpoints.html.home-be
  (:require
   [auto-web.page.builder                  :refer [print-css-meta]]
   [landing.endpoints.html                 :refer [html-middlewares]]
   [landing.endpoints.html.public-pages-be :refer [public-page-header]]
   [landing.hiccup                         :refer [render-html]]
   [landing.pages.home                     :refer [body home-dic]]
   [landing.pages.structure                :refer [links]]))

(defn home-page
  [http-request]
  (let [l (:lang http-request)
        tr #(get-in home-dic [% l])]
    [:html {:lang (:lang http-request)}
     (vec (concat [:head]
                  (public-page-header http-request (tr :home-header-title) (tr :home-header-desc))
                  [(print-css-meta (:print-css links))]))
     (body http-request)]))

(defn home-response
  [http-request]
  {:status 200
   :headers {"content-type" "text/html"
             "Access-Control-Allow-Origin" "*"}
   :body (render-html (home-page http-request))})

(defn home-route
  [prefix]
  [prefix {:get {:handler home-response
                 :swagger {:tags #{:html}}
                 :summary "Home page"
                 :middleware html-middlewares}}])
