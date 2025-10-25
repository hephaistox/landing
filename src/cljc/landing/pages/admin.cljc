(ns landing.pages.admin
  (:require
   [auto-web.components.badge :refer [cspinner]]
   [landing.article.digital-twin]
   [landing.article.hephaistox]
   [landing.article.project]
   [landing.article.rivalis]
   [landing.article.who-are-we]
   [landing.pages.error]
   [landing.pages.home]
   [landing.pages.structure]
   [landing.routes]))

(defn admin-body
  [_http-request]
  [:body.w3-xlarge.w3-panel {:style {:user-select "none"}}
   [:div#admin-panel (cspinner {})]])

(defn add-origin [links origin] (map #(assoc % :origin origin) links))

(defn links
  [pings-env]
  (concat (add-origin [{:link-id :prod-https-com
                        :url "https://hephaistox.com"}
                       {:link-id :prod-https-fr
                        :url "https://hephaistox.fr"}
                       {:link-id :prod-http-com
                        :url "http://hephaistox.com"}
                       {:link-id :prod-http-fr
                        :url "http://hephaistox.fr"}
                       {:link-id :prod-https-com-www
                        :url "https://www.hephaistox.com"}
                       {:link-id :prod-https-fr-www
                        :url "https://www.hephaistox.fr"}
                       {:link-id :prod-http-com-www
                        :url "http://www.hephaistox.com"}
                       {:link-id :prod-http-fr-www
                        :url "http://www.hephaistox.fr"}]
                      "landing.admin")
          (add-origin pings-env :env-dependant)
          (add-origin (vals landing.article.rivalis/links) "landing.article.rivalis")
          (add-origin (vals landing.article.hephaistox/links) "landing.article.hephaistox")
          (add-origin (vals landing.article.who-are-we/links) "landing.article.who-are-we")
          (add-origin (vals landing.article.project/links) "landing.article.project")
          (add-origin (vals landing.routes/links) "landing.routes")
          (add-origin (vals landing.pages.structure/links) "landing.pages.structure")))

(def images
  (concat (add-origin (vals landing.article.rivalis/images) "landing.article.rivalis")
          (add-origin (vals landing.routes/images) "landing.routes")
          (add-origin (vals landing.pages.home/images) "landing.pages.home")
          (add-origin (vals landing.article.project/images) "landing.article.project")
          (add-origin (vals landing.article.digital-twin/images) "landing.article.digital-twin")
          (add-origin (vals landing.article.who-are-we/images) "landing.article.who-are-we")))

(def dics
  {"landing.pages.home" landing.pages.home/home-dic
   "landing.article.who-are-we" landing.article.who-are-we/who-are-we-dic
   "landing.routes" landing.routes/routes-dic
   "landing.pages.error" landing.pages.error/error-dic})
