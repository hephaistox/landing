(ns landing.pages.structure-be
  (:require
   [auto-web.page.builder   :refer [X-prepared-page-metas all-pages-metas css-meta seo-page-metas]]
   [landing.pages.structure :refer [links profile]]))

(defn public-page-header
  "Create an html5 page, in utf8, taking the whole app

  The page is known by the SEO and twitter"
  [http-request title description]
  (let [{:keys [author-name page-snapshot permanent-url icon X-profile-url]} profile]
    (vec
     (concat [[:meta {:charset "utf-8"}]
              (css-meta (:w3-schools links))
              (css-meta (:w3-color-flat links))
              (css-meta (:component-css links))
              (css-meta (:custom-css links))
              ;; https://docs.fontawesome.com/web/setup/host-yourself/webfonts
              ;;(css-meta (:local-fa-all links))
              (css-meta (:local-fa links))
              (css-meta (:local-fa-brands links))
              (css-meta (:local-fa-solid links))
              ;; (css-meta (:local-fa-sharp-thin links))
              ;; (css-meta (:local-fa-duotone-thin links))
              ;; (css-meta (:local-fa-sharp-duotone-thin links))
              [:meta {:content "width=device-width,initial-scale=1"
                      :name "viewport"}]]
             (all-pages-metas title icon (:lang http-request))
             (seo-page-metas author-name description page-snapshot permanent-url)
             (X-prepared-page-metas description X-profile-url page-snapshot)))))
