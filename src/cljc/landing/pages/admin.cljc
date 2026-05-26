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

(def ^:private html-slugs
  "Pages to W3C-validate, named in a stable display form. Each entry is
  expanded into one fr and one en check below, hitting the real static
  file directly (no /articles/<slug> → /<lang>/articles/<slug>.html
  redirect to depend on)."
  [[:home              "index.html"]
   [:rivalis           "articles/rivalis.html"]
   [:projets           "articles/projets.html"]
   [:hephaistox        "articles/hephaistox.html"]
   [:contacts          "articles/contacts.html"]
   [:about-site        "articles/about-site.html"]
   [:legal-notice      "articles/legal-notice.html"]
   [:privacy           "articles/privacy.html"]
   [:disclaimer        "articles/disclaimer.html"]
   [:who-are-we        "articles/who-are-we.html"]])

(def w3c-validate-htmls
  "Flat map of `:<slug>-<lang>` → absolute static path under the site root.
  Both languages are checked for every public page."
  (into {}
        (for [[slug path] html-slugs
              lang ["fr" "en"]]
          [(keyword (str (name slug) "-" lang))
           (str lang "/" path)])))

(def w3c-validate-css
  {:w3-school "css/w3_schools.css"
   :colors-flat "css/w3_colors_flat.css"
   :component "css/components.css"
   :fontawesome "fontawesome/css/fontawesome.css"
   :brand "fontawesome/css/brands.css"
   :solid "fontawesome/css/solid.css"
   :print "print.css"
   :custom "custom.css"})
