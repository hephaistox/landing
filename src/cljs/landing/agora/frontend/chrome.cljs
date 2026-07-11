(ns landing.agora.frontend.chrome
  "Agora app chrome: the header (nav, search, auth) and the footer, plus the search box
  and its `::search` events. Rendered by `core`'s root layout around the current page."
  (:require
   [clojure.string                       :as str]
   [landing.agora.frontend.auth          :as auth]
   [landing.agora.frontend.document-page :as    dv
                                         :refer [kind-badge permalink version-tag-style]]
   [landing.agora.frontend.i18n          :as i18n]
   [re-frame.core                        :as rf]
   [superstructor.re-frame.fetch-fx]))

(rf/reg-sub ::search (fn [db _] (::search db)))
(rf/reg-event-db ::search-clear (fn [db _] (dissoc db ::search)))

(rf/reg-event-fx ::search-input
                 (fn [{:keys [db]} [_ q]]
                   (if (str/blank? q)
                     {:db (assoc db
                                 ::search
                                 {:q q
                                  :results []})}
                     {:db (assoc-in db [::search :q] q)
                      :fetch {:method :get
                              :url (str "/agora/api/ki?lang=" (i18n/current db)
                                        "&q=" (js/encodeURIComponent q))
                              :headers {"Accept" "application/json"}
                              :response-content-types {#"application/json" :json}
                              :on-success [::search-ok]
                              :on-failure [::op-failed]}})))

(rf/reg-event-db ::search-ok (fn [db [_ resp]] (assoc-in db [::search :results] (:body resp))))

(defn search-box
  "A search input that queries name + output statement and shows matches as a
  dropdown of links to public KI pages."
  []
  (let [{:keys [q results]} @(rf/subscribe [::search])
        lang @(rf/subscribe [::i18n/lang])]
    [:div {:style {:position "relative"
                   :width "100%"}}
     [:input {:type "text"
              :placeholder (i18n/t lang :search/placeholder)
              :value (or q "")
              :on-change #(rf/dispatch [::search-input (.. % -target -value)])
              :style {:width "100%"
                      :box-sizing "border-box"
                      :padding "0.55em"
                      :font-size "1em"
                      :border "1px solid #ccc"
                      :border-radius "0.4em"}}]
     (when (and (not (str/blank? q)) (seq results))
       (into [:div {:style {:position "absolute"
                            :z-index 20
                            :left 0
                            :right 0
                            :margin-top "0.2em"
                            :background "#fff"
                            :border "1px solid #ddd"
                            :border-radius "0.4em"
                            :box-shadow "0 4px 12px rgba(0,0,0,0.1)"
                            :max-height "20em"
                            :overflow-y "auto"}}]
             (for [k results]
               ^{:key (:id k)}
               [:a {:href (permalink lang k)
                    :on-click #(rf/dispatch [::search-clear])
                    :style {:display "flex"
                            :align-items "center"
                            :gap "0.5em"
                            :padding "0.5em 0.7em"
                            :text-decoration "none"
                            :color "inherit"
                            :border-bottom "1px solid #f0f0f0"}}
                [kind-badge (:kind k)]
                [:span {:style {:font-weight 600}}
                 (:name k)]
                [:span {:style version-tag-style}
                 (str "v" (:major k) "." (:minor k))]])))
     (when (and (not (str/blank? q)) (empty? results))
       [:div {:style {:position "absolute"
                      :z-index 20
                      :left 0
                      :right 0
                      :margin-top "0.2em"
                      :background "#fff"
                      :border "1px solid #ddd"
                      :border-radius "0.4em"
                      :padding "0.5em 0.7em"
                      :color "#aaa"
                      :font-size "0.9em"}}
        (i18n/t lang :search/no-matches)])]))

(defn header
  "Shared Agora header (Hephaistox dark/copper theme): the two discover links, search
  and auth controls. Creation is a `+` card at the end of each discover grid, not a
  header link. The interface language is a preference, set on the Preferences page —
  not here; a signed-in visitor gets the editable page automatically when viewing a KI."
  []
  (let [lang @(rf/subscribe [::i18n/lang])]
    [:header {:class "agora-header"
              :style {:background "#1b1a17"
                      :color "#e8e2d6"
                      :border-bottom "2px solid #b9770e"}}
     [:a {:href (i18n/home lang)
          :style {:font-family "Georgia, 'Cormorant Garamond', serif"
                  :font-size "1.4em"
                  :font-weight 700
                  :letter-spacing "0.03em"
                  :color "#d99a2b"
                  :text-decoration "none"}}
      "Agora"]
     ;; Just the two discover links; creation is a `+` card on each discover grid.
     (let [link (fn [label href] [:a {:key href
                                      :href href
                                      :style {:color "#e8e2d6"
                                              :text-decoration "none"
                                              :opacity 0.85}}
                                  label])]
       [:nav {:style {:display "flex"
                      :align-items "center"
                      :gap "1.1em"
                      :font-size "0.9em"
                      :flex-wrap "wrap"}}
        (link (i18n/t lang :nav/discover-ki) (i18n/discover lang))
        (link (i18n/t lang :nav/discover-articles) (i18n/articles lang))
        (link (i18n/t lang :nav/authors) (i18n/authors lang))
        (link (i18n/t lang :nav/sources) (i18n/sources lang))])
     [:div {:class "agora-header__search"}
      [search-box]]
     [:div {:class "agora-header__auth"}
      [auth/auth-controls]]]))

(def ^:private footer-legal
  "Legal / info links, adapted from the hephaistox.com landing footer. Paths are
  under the language root of the main site (outside Agora)."
  [[:footer/home "index.html"]
   [:footer/legal-notice "articles/legal-notice.html"]
   [:footer/privacy "articles/privacy.html"]
   [:footer/disclaimer "articles/disclaimer.html"]
   [:footer/who-are-we "articles/who-are-we.html"]])

(def ^:private footer-social
  "Social links as FontAwesome brand icons, mirroring the hephaistox.com footer."
  [["LinkedIn" "fa-linkedin" "https://www.linkedin.com/company/hephaistox"]
   ["Facebook" "fa-facebook-f" "https://www.facebook.com/profile.php?id=61586135248424"]
   ["YouTube" "fa-youtube" "https://www.youtube.com/@HephaistoxSC"]
   ["GitHub" "fa-github" "https://github.com/hephaistox"]])

(defn site-footer
  "Agora footer, adapted from the hephaistox.com landing footer: legal/info links
  (to the main site, language-rooted), social icons, and copyright. Themed to
  match the header (dark/copper)."
  []
  (let [lang @(rf/subscribe [::i18n/lang])
        link {:color "#d9b38c"
              :text-decoration "none"
              :font-size "0.85em"}
        row {:display "flex"
             :flex-wrap "wrap"
             :justify-content "center"
             :gap "0.4em 1.2em"
             :margin-bottom "0.9em"}]
    [:footer {:style {:flex-shrink 0
                      :padding "1.6em 1.2em"
                      :background "#1b1a17"
                      :color "#e8e2d6"
                      :border-top "2px solid #b9770e"
                      :text-align "center"
                      :font-family "system-ui, sans-serif"}}
     (into [:div {:style row}]
           (concat [^{:key "prefs"}
                    [:a {:href (i18n/preferences lang)
                         :style link}
                     (i18n/t lang :nav/preferences)]]
                   (for [[k path] footer-legal]
                     ^{:key path}
                     [:a {:href (str "/" lang "/" path)
                          :style link}
                      (i18n/t lang k)])))
     (into [:div {:style (assoc row :gap "0.2em 1.4em" :font-size "1.35em")}]
           (for [[label icon url] footer-social]
             ^{:key label}
             [:a {:href url
                  :target "_blank"
                  :rel "noopener noreferrer"
                  :title label
                  :aria-label label
                  :style {:color "#d9b38c"
                          :text-decoration "none"
                          :line-height 1
                          :padding "0.15em"}}
              [:i {:class (str "fa-brands " icon)}]]))
     [:div {:style {:font-size "0.8em"
                    :color "#8a8377"}}
      "Hephaistox © 2026"]]))

