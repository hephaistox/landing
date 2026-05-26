(ns landing.pages.admin
  "Pure-data manifests aggregated for the admin SPA: every URL the site should
  expose (for reachability checks) and every page/CSS file the SPA should
  W3C-validate. Not a rendered page."
  (:require
   [landing.article.rivalis]
   [landing.article.who-are-we]
   [landing.routes]))

(defn add-origin [links origin] (map #(assoc % :origin origin) links))

(def links
  "Every link the admin page should reachability-check."
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
          (add-origin (vals landing.article.rivalis/links) "landing.article.rivalis")
          (add-origin (vals landing.article.who-are-we/links) "landing.article.who-are-we")
          (add-origin (vals landing.routes/links) "landing.routes")))

(def w3c-validate-htmls
  {:privacy "articles/privacy"
   :home ""
   :rivalis "articles/rivalis"
   :projets "articles/projets"
   :hephaistox "articles/hephaistox"
   :contact "articles/contacts"
   :about "articles/about-site"
   :legal-notice "articles/legal-notice"
   :disclaimer "articles/disclaimer"
   :who-are-we "articles/who-are-we"})

(def w3c-validate-css
  {:w3-school "css/w3_schools.css"
   :colors-flat "css/w3_colors_flat.css"
   :component "css/components.css"
   :fontawesome "fontawesome/css/fontawesome.css"
   :brand "fontawesome/css/brands.css"
   :solid "fontawesome/css/solid.css"
   :print "print.css"
   :custom "custom.css"})
