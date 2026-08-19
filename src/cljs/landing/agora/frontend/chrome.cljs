(ns landing.agora.frontend.chrome
  "Agora app chrome: the header (nav, active-publication chip, auth) and the footer. Rendered by
  `core`'s root layout around the current page. Browse/search is per-view (the shared filter bar)."
  (:require
   [landing.agora.frontend.auth         :as auth]
   [landing.agora.frontend.i18n         :as i18n]
   [landing.agora.frontend.publications :as publications]
   [landing.agora.frontend.ui-commons   :as ui]
   [re-frame.core                       :as rf]
   [reagent.core                        :as r]))

(def ^:private explore-entries
  "Every browse surface the app offers, gathered under the `Explorer` menu: the knowledge feed, the
  author index, the publications index and the FAQ. One place holding the whole set, so the primary
  nav can stay at what a visitor does — read an article, work in a publication. `[label-key url-fn]`."
  [[:nav/discover-ki i18n/discover]
   [:nav/authors i18n/authors]
   [:nav/publications i18n/publications]
   [:nav/faq i18n/faq]])

(defn- explore-menu
  "The secondary nav, as a dropdown: smaller and dimmer than the primary entries, and set further
  from them — articles are the way in, these are the ways around. Closes on Escape and on picking
  an entry."
  [lang]
  (r/with-let
   [open? (r/atom false)]
   [:div {:style {:position "relative"
                  :margin-left "1.4em"}}
    [:button {:on-click #(swap! open? not)
              :aria-expanded @open?
              :aria-haspopup "menu"
              :style {:border "none"
                      :background "transparent"
                      :padding 0
                      :cursor "pointer"
                      :font-family "inherit"
                      :font-size "0.8em"
                      :color "#e8e2d6"
                      :opacity 0.7}}
     (str (i18n/t lang :nav/explore) " ▾")]
    (when @open?
      [:<>
       [ui/on-escape #(reset! open? false)]
       (into [:div {:role "menu"
                    :style {:position "absolute"
                            :left 0
                            :top "2em"
                            :z-index 30
                            :min-width "11em"
                            :background "#fff"
                            :color "#222"
                            :border "1px solid #ddd"
                            :border-radius "0.4em"
                            :box-shadow "0 4px 12px rgba(0,0,0,0.15)"
                            :padding "0.4em"}}]
             (for [[k href] explore-entries]
               ^{:key (str k)}
               [:a {:href (href lang)
                    :role "menuitem"
                    :on-click #(reset! open? false)
                    :style {:display "block"
                            :padding "0.4em 0.5em"
                            :color "#333"
                            :font-size "0.95em"
                            :text-decoration "none"}}
                (i18n/t lang k)]))])]))

(defn header
  "Shared Agora header (Hephaistox dark/copper theme). Two nav groups: the primary one is what a
  visitor comes to do — read (Articles) and write (the active-publication chip) — and the `Explorer`
  dropdown holds every other browse surface, further out and smaller. Creation is the floating
  create control, not a header link. Browse/search is per-view (the shared filter bar). The
  interface language is a preference, set on the Preferences page — not here; a signed-in visitor
  gets the editable page automatically."
  []
  (let [lang @(rf/subscribe [::i18n/lang])]
    [:header {:class "agora-header"
              :style {:background "#1b1a17"
                      :color "#e8e2d6"
                      :border-bottom "2px solid #b9770e"}}
     ;; brand + a Google-style « Bêta » badge (a sibling link, never nested inside the brand anchor),
     ;; present on every page and linking to the beta notice + roadmap
     [:div {:style {:display "flex"
                    :align-items "flex-start"
                    :gap "0.3em"}}
      [:a {:href (i18n/home lang)
           :style {:font-family "Georgia, 'Cormorant Garamond', serif"
                   :font-size "1.4em"
                   :font-weight 700
                   :letter-spacing "0.03em"
                   :color "#d99a2b"
                   :text-decoration "none"}}
       "Agora"]
      [:a {:href (i18n/beta lang)
           :title (i18n/t lang :beta/hint)
           :style {:font-size "0.6em"
                   :font-weight 700
                   :letter-spacing "0.08em"
                   :text-transform "uppercase"
                   :color "#1b1a17"
                   :background "#d99a2b"
                   :padding "0.15em 0.45em"
                   :border-radius "0.25em"
                   :text-decoration "none"
                   :margin-top "0.25em"
                   :line-height 1}}
       (i18n/t lang :beta/badge)]]
     ;; Primary: the two things a visitor is here for — read an article, and (signed in) the
     ;; publication everything they create attaches to. Creation itself is the floating control.
     [:nav {:style {:display "flex"
                    :align-items "center"
                    :gap "1.1em"
                    :font-size "0.9em"
                    :flex-wrap "wrap"}}
      [:a {:href (i18n/articles lang)
           :style {:color "#e8e2d6"
                   :text-decoration "none"}}
       (i18n/t lang :nav/discover-articles)]
      [publications/active-chip]]
     ;; Secondary: every other browse surface, one step further out.
     [explore-menu lang]
     ;; profile, at the right edge of the header
     [:div {:style {:margin-left "auto"
                    :display "flex"
                    :align-items "center"}}
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

