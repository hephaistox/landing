(ns landing.pages.structure
  "Common elements for all pages."
  (:require
   [auto-web.components.badge :refer [copyright-str]]
   [auto-web.components.img   :refer [cicon]]
   [auto-web.components.link  :refer [link-opts]]
   [auto-web.components.list  :refer [csmall-imgs]]
   [auto-web.components.menu  :refer [chorizontal-text-menu]]
   [landing.language          :refer [landing-lang-bar]]
   [landing.routes            :as lroutes]))

;; ********************************************************************************
;; Data
;; ********************************************************************************

(def links
  (->> [{:url "/css/w3_schools.css"
         :link-id :w3-schools}
        {:url "/css/w3_colors_flat.css"
         :link-id :w3-color-flat}
        {:url "/css/components.css"
         :link-id :component-css}
        {:url "/print.css"
         :link-id :print-css}
        {:url "/custom.css"
         :link-id :custom-css}
        {:url "/fontawesome/css/all.css"
         :link-id :local-fa-all}
        {:url "/fontawesome/css/fontawesome.css"
         :link-id :local-fa}
        {:url "/fontawesome/css/brands.css"
         :link-id :local-fa-brands}
        {:url "/fontawesome/css/solid.css"
         :link-id :local-fa-solid}
        {:url "js/compiled/app-admin.js"
         :link-id :reframe-admin}
        {:url "js/compiled/app.js"
         :link-id :reframe}]
       (mapv (fn [link] [(:link-id link) link]))
       (into {})))

(def profile
  {:page-snapshot "/img/preview/en.png"
   :icon "/favicon.ico"
   :author-name "Hephaistox"
   :X-profile-url ""
   :permanent-url "https://hephaistox.com"})

(def footer-menu-items
  (vals (select-keys lroutes/links [:home :legal-notice :privacy :disclaimer :who-are-we])))

(defn- open-sidebar-menu [] "document.getElementById(\"menu-sidebar\").style.display = \"block\";")
(defn- close-sidebar-menu [] "document.getElementById(\"menu-sidebar\").style.display = \"none\";")

;; ********************************************************************************
;; Components
;; ********************************************************************************

(defn cheader
  "An header presenting logo, burger menu to open the menu in small screens,
  and a lang bar on the right"
  [opts http-request]
  [:header#header.w3-panel.w3-display-container
   opts
   [:div.w3-display-left
    [:div.w3-bar
     (cicon {:class "w3-xlarge w3-hide-large w3-margin"
             :style {:cursor "pointer"}
             :onclick (open-sidebar-menu)}
            "fa-bars")]]
   [:div.w3-right (landing-lang-bar {} http-request)]])

(defn cfooter
  "A footer showing contacts and legal lnks, most suitable for public pages."
  [opts http-request]
  (let [l (:lang http-request)
        tr #(get-in lroutes/routes-dic [% l])]
    [:footer#footer-section.w3-flat-midnight-blue.w3-row.w3-display-container.w3-padding.w3-small
     opts
     [:div.w3-center.w3-padding-small
      (chorizontal-text-menu {} (mapv #(update % :text tr) footer-menu-items))]
     [:div.w3-center.w3-padding-small
      (csmall-imgs {} (vals (select-keys lroutes/social [:linkedin :github :youtube])))]
     [:div.w3-center.w3-padding-small (copyright-str)]]))

(defn csidebar
  [opt http-request]
  (let [l (:lang http-request)
        tr #(get-in lroutes/routes-dic [% l])]
    [:div#menu-sidebar.w3-sidebar.w3-bar-block.w3-collapse.w3-padding.w3-animate-left.w3-small
     opt
     [:a.w3-bar-item.w3-button (link-opts (:home lroutes/links)) (tr :home)]
     [:hr]
     [:a.w3-bar-item.w3-button (link-opts (:rivalis lroutes/links)) (tr :rivalis)]
     [:a.w3-bar-item.w3-button (link-opts (:projets lroutes/links)) (tr :projets)]
     [:hr]
     [:a.w3-bar-item.w3-button (link-opts (:hephaistox lroutes/links)) (tr :hephaistox)]
     [:a.w3-bar-item.w3-button (link-opts (:contacts lroutes/links)) (tr :contacts)]
     [:a.w3-bar-item.w3-button
      (link-opts (:about-this-website lroutes/links))
      (tr :about-this-website)]
     [:button.w3-button.w3-large.w3-display-topright.w3-cell.w3-hide-large {:onclick
                                                                            (close-sidebar-menu)}
      [:i.fa.fa-times]]]))
