(ns landing.endpoints.html.admin-be
  "Backend endpoint for an administration page"
  (:require
   [auto-web.page.builder                  :refer [js-script-link]]
   [landing.endpoints.html                 :refer [html-middlewares]]
   [landing.endpoints.html.public-pages-be :refer [public-page-header]]
   [landing.hiccup                         :refer [render-html]]
   [landing.pages.admin                    :refer [admin-body]]
   [landing.pages.structure                :refer [links]]))

(defn admin-page
  "The page is a regular html page with javascript"
  [http-request]
  [:html {:lang (:lang http-request)}
   (vec (concat [:head]
                (public-page-header http-request "Hephaistox error page" "This should not happen")))
   (admin-body http-request)
   (js-script-link (:reframe-admin links))])

(defn admin-response-wo-body
  "Admin page is a bit special as it is authorizing some external websites."
  [_]
  {:status 200
   :headers {"content-type" "text/html"
             "access-control-allow-methods" [:get :put :post :delete]
             "access-control-allow-origin" "*"}})

(defn admin-response
  "Admin page is a bit special as it is authorizing some external websites."
  [http-request]
  (-> (admin-response-wo-body http-request)
      (assoc :body (render-html (admin-page http-request)))))

(defn admin-route
  [prefix]
  [prefix {:get {:swagger {:tags #{:html}}
                 :handler admin-response
                 :middleware html-middlewares
                 :summary "Administration page"}
           :head {:swagger {:tags #{:html}}
                  :handler admin-response-wo-body
                  :middleware html-middlewares
                  :summary "Administration page"}}])
