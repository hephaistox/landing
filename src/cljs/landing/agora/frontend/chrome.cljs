(ns landing.agora.frontend.chrome
  "Agora app chrome: the header (nav, active-publication chip, auth) and the footer. Rendered by
  `core`'s root layout around the current page. Browse/search is per-view (the shared filter bar)."
  (:require
   [landing.agora.frontend.auth         :as auth]
   [landing.agora.frontend.i18n         :as i18n]
   [landing.agora.frontend.publications :as publications]
   [re-frame.core                       :as rf]))

(defn header
  "Shared Agora header (Hephaistox dark/copper theme): the discover links, the active-publication
  chip and auth controls. Creation is a `+` card at the end of each discover grid, not a header
  link. Browse/search is per-view (the shared filter bar). The interface language is a preference,
  set on the Preferences page — not here; a signed-in visitor gets the editable page automatically."
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
        (link (i18n/t lang :nav/authors) (i18n/authors lang))])
     ;; Publications lives to the right as the active-publication chip (see `active-chip`)
     ;; active-publication chip + profile, grouped to the right edge of the header
     [:div {:style {:margin-left "auto"
                    :display "flex"
                    :align-items "center"
                    :gap "0.6em"}}
      [publications/active-chip]
      [auth/auth-controls]]]))

(def ^:private footer-legal
  "Legal / info links, adapted from the hephaistox.com landing footer. Paths are
  under the language root of the main site (outside Agora)."
  [[:footer/legal-notice "articles/legal-notice.html"]
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
           (concat [^{:key "faq"}
                    [:a {:href (i18n/faq lang)
                         :style link}
                     (i18n/t lang :nav/faq)]]
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

