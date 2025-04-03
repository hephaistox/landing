(ns landing.pages.structure
  "Common elements for all pages."
  (:require
   [auto-web.components.badge :refer [copyright-str]]
   [auto-web.components.img   :refer [cicon cimg]]
   [auto-web.components.link  :refer [clink]]
   [auto-web.components.list  :refer [csmall-imgs]]
   [auto-web.components.menu  :refer [chorizontal-text-menu]]
   [landing.language          :refer [landing-lang-bar]]
   [landing.routes            :as lroutes]))

;; ********************************************************************************
;; Data
;; ********************************************************************************

(def links
  (->> [{:url "/css/w3_schools.css"
         :id :w3-schools}
        {:url "/css/w3_colors_flat.css"
         :id :w3-color-flat}
        {:url "simulation/workshop.css"
         :id :workshop}
        {:url "/css/components.css"
         :id :component-css}
        {:url "/custom.css"
         :id :custom-css}
        {:url "/https://kit.fontawesome.com/4bcf978f75.js"
         :id :fontawesomejs}
        {:url "/fontawesome/css/all.css"
         :id :local-fa-all}
        {:url "/fontawesome/css/fontawesome.css"
         :id :local-fa}
        {:url "/fontawesome/css/brands.css"
         :id :local-fa-brands}
        {:url "/fontawesome/css/solid.css"
         :id :local-fa-solid}
        {:url "/fontawesome/css/sharp-thin.css"
         :id :local-fa-sharp-thin}
        {:url "/fontawesome/css/duotone-thin.css"
         :id :local-fa-duotone-thin}
        {:url "js/compiled/app-admin.js"
         :id :reframe-admin}
        {:url "js/compiled/app.js"
         :id :reframe}
        {:url "/fontawesome/css/sharp-duotone-thin.css"
         :id :local-fa-sharp-duotone-thin}]
       (mapv (fn [link] [(:id link) link]))
       (into {})))

(def profile
  {:page-snapshot "/img/preview/en.png"
   :icon "/favicon.ico"
   :author-name "Hephaistox"
   :X-profile-url ""
   :permanent-url "https://hephaistox.com"})

(def in-menu-items [:home :privacy :disclaimer])

;; ********************************************************************************
;; Components
;; ********************************************************************************

(defn cheader
  "An header presenting logo, burger menu to open the menu in small screens,
  and a lang bar on the right"
  [opts http-request]
  [:header.w3-panel.w3-display-container
   opts
   [:div.w3-display-left
    [:div.w3-bar
     (clink {} (:home lroutes/links) (cimg {} :tiny (:hephaistox-logo lroutes/images)))
     (cicon {:class "w3-xlarge w3-hide-large w3-margin"
             :onclick "document.getElementById(\"menu-sidebar\").style.display = \"block\";"}
            [:i.fa.fa-bars])]]
   [:div.w3-right (landing-lang-bar {} http-request)]])

(defn cfooter
  "A footer showing contacts and legal lnks, most suitable for public pages."
  [opts http-request]
  (let [l (:lang http-request)
        tr #(get-in lroutes/dic [% l])]
    [:footer#footer-section.w3-flat-midnight-blue.w3-row.w3-display-container.w3-padding.w3-small
     opts
     [:div.w3-center.w3-padding-small
      (chorizontal-text-menu {}
                             (mapv #(update % :text tr)
                                   (vals (select-keys lroutes/links in-menu-items))))]
     [:div.w3-center.w3-padding-small
      (csmall-imgs {} (vals (select-keys lroutes/social [:linkedin :mail :github :youtube])))]
     [:div.w3-center.w3-padding-small (copyright-str)]]))

(defn csidebar
  [opt http-request]
  (let [l (:lang http-request)
        opt-item {:class "w3-bar-item w3-button"}
        tr #(get-in lroutes/dic [% l])]
    [:div#menu-sidebar.w3-sidebar.w3-bar-block.w3-collapse.w3-padding.w3-animate-left
     opt
     [:button.w3-button.w3-large.w3-hide-large.w3-right
      {:onclick "document.getElementById(\"menu-sidebar\").style.display = \"none\";"}
      [:i.fa.fa-times]]
     (clink opt-item (:home lroutes/links) (tr :home))
     [:hr]
     (clink opt-item (:simulation lroutes/links) (tr :simulation))
     [:hr]
     (clink opt-item (:privacy lroutes/links) (tr :privacy))
     (clink opt-item (:disclaimer lroutes/links) (tr :disclaimer))
     (clink opt-item (:contact lroutes/links) (tr :contact))]))
