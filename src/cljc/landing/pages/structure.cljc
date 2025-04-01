(ns landing.pages.structure
  "Common elements for all pages."
  (:require
   [auto-web.components.v-button    :refer [v-button]]
   [auto-web.components.v-copyright :refer [copyright-str]]
   [auto-web.components.v-img       :refer [v-img]]
   [auto-web.components.v-link      :refer [v-a]]
   [auto-web.components.v-list      :as wvlist]
   [auto-web.components.v-menu      :refer [horizontal-text-menu]]
   [landing.language                :refer [lang-bar]]
   [landing.routes                  :as lroutes]))

;; ********************************************************************************
;; Data
;; ********************************************************************************

(def links
  (->> [{:url "/css/w3_schools.css"
         :name :w3-schools}
        {:url "/css/w3_colors_flat.css"
         :name :w3-color-flat}
        {:url "/css/components.css"
         :name :component-css}
        {:url "/custom.css"
         :name :custom-css}
        {:url "/https://kit.fontawesome.com/4bcf978f75.js"
         :name :fontawesomejs}
        {:url "/fontawesome/css/all.css"
         :name :local-fa-all}
        {:url "/fontawesome/css/fontawesome.css"
         :name :local-fa}
        {:url "/fontawesome/css/brands.css"
         :name :local-fa-brands}
        {:url "/fontawesome/css/solid.css"
         :name :local-fa-solid}
        {:url "/fontawesome/css/sharp-thin.css"
         :name :local-fa-sharp-thin}
        {:url "/fontawesome/css/duotone-thin.css"
         :name :local-fa-duotone-thin}
        {:url "/fontawesome/css/sharp-duotone-thin.css"
         :name :local-fa-sharp-duotone-thin}]
       (mapv (fn [link] [(:name link) link]))
       (into {})))

(def profile
  {:page-snapshot "/img/preview/en.png"
   :icon "/favicon.ico"
   :author-name "Hephaistox"
   :X-profile-url ""
   :permanent-url "https://hephaistox.com"})

(def in-menu-items [:hephaistox :privacy :disclaimer])

;; ********************************************************************************
;; Helpers
;; ********************************************************************************

(def images
  (->> [{:url "/images/logos/hephaistox_logo.png"
         :alt "Logo hephaistox"
         :name :hephaistox-logo}]
       (mapv (fn [link] [(:name link) link]))
       (into {})))

;; ********************************************************************************
;; Components
;; ********************************************************************************

(defn header
  "An header presenting logo, burger menu to open the menu in small screens,
  and a lang bar on the right"
  [http-request & opts]
  [:header.w3-panel.w3-display-container
   (first opts)
   [:div.w3-display-left
    [:div.w3-bar
     (v-a (:home lroutes/links) (v-img (:hephaistox-logo images) :tiny))
     (v-button ""
               [:i.fa.fa-bars]
               {:class "w3-xlarge w3-hide-large w3-margin"
                :onclick "document.getElementById(\"menu-sidebar\").style.display = \"block\";"})]]
   [:div.w3-right (lang-bar http-request)]])

(defn footer
  "A footer showing contacts and legal lnks, most suitable for public pages."
  [http-request & opts]
  (let [l (:lang http-request)
        tr #(get-in lroutes/dic [% l])]
    [:footer#footer-section.w3-flat-midnight-blue.w3-row.w3-display-container.w3-padding.w3-small
     (first opts)
     [:div.w3-center.w3-padding-small
      (horizontal-text-menu (update-vals (select-keys lroutes/links in-menu-items)
                                         #(update % :text tr)))]
     [:div.w3-center.w3-padding-small
      (wvlist/v-small-icons (vals (select-keys lroutes/social [:linkedin :mail :github :youtube])))]
     [:div.w3-center.w3-padding-small (copyright-str)]]))

(defn sidebar
  [http-request & opts]
  (let [l (:lang http-request)
        opt-item {:class "w3-bar-item w3-button"}
        tr #(get-in lroutes/dic [% l])]
    [:div#menu-sidebar.w3-sidebar.w3-bar-block.w3-collapse.w3-padding.w3-animate-left
     (first opts)
     [:button.w3-button.w3-large.w3-hide-large.w3-right
      {:onclick "document.getElementById(\"menu-sidebar\").style.display = \"none\";"}
      [:i.fa.fa-times]]
     (v-a (:home lroutes/links) (tr :home) opt-item)
     [:hr]
     (v-a (:simulation lroutes/links) (tr :simulation) opt-item)
     [:hr]
     (v-a (:privacy lroutes/links) (tr :privacy) opt-item)
     (v-a (:disclaimer lroutes/links) (tr :disclaimer) opt-item)
     (v-a (:contact lroutes/links) (tr :contact) opt-item)]))
