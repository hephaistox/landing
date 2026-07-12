(ns landing.agora.frontend.landing
  "The Agora landing/home page (`/agora/<lang>`): a marketing hero, the four things Agora
  lets you do, how it works, a detail paragraph per value prop, and a closing call to action."
  (:require
   [landing.agora.frontend.i18n :as i18n]
   [re-frame.core               :as rf]))

(defn- landing-hero
  "The marketing banner atop the landing page: eyebrow, pitch, the primary explore /
  publish actions, and a reassurance line — so the value prop lands above the fold."
  [lang]
  [:section {:style {:background "linear-gradient(160deg, #1b1a17, #262019)"
                     :color "#e8e2d6"
                     :border-radius "0.8em"
                     :padding "2.6em 1.4em"
                     :margin-bottom "1.4em"
                     :text-align "center"}}
   [:div {:style {:font-size "0.78em"
                  :font-weight 700
                  :text-transform "uppercase"
                  :letter-spacing "0.14em"
                  :color "#d69a3a"
                  :margin-bottom "0.9em"}}
    (i18n/t lang :home/eyebrow)]
   [:h1 {:style {:font-family "Georgia, 'Cormorant Garamond', serif"
                 :font-size "clamp(1.7em, 4.4vw, 2.7em)"
                 :line-height "1.16"
                 :margin "0 0 0.5em"
                 :color "#f0e6d2"}}
    (i18n/t lang :landing/headline)]
   [:p {:style {:max-width "42em"
                :margin "0 auto 1.5em"
                :font-size "1.08em"
                :line-height "1.6"
                :color "#c9c1b2"}}
    (i18n/t lang :home/subtitle)]
   [:div {:style {:display "flex"
                  :flex-wrap "wrap"
                  :gap "0.7em"
                  :justify-content "center"}}
    [:a {:href (i18n/discover lang)
         :style {:padding "0.65em 1.4em"
                 :background "#b9770e"
                 :color "#fff"
                 :border-radius "0.4em"
                 :font-weight 600
                 :text-decoration "none"}}
     (i18n/t lang :home/cta-explore)]
    [:a {:href (i18n/new-ki lang)
         :style {:padding "0.65em 1.4em"
                 :background "transparent"
                 :color "#e8e2d6"
                 :border "1px solid #b9770e"
                 :border-radius "0.4em"
                 :text-decoration "none"}}
     (i18n/t lang :home/cta-publish)]]
   [:div {:style {:margin-top "1.3em"
                  :font-size "0.82em"
                  :color "#8f8776"}}]])

(defn- home-section-heading
  "Serif section title, centered — the recurring divider between landing sections."
  [text]
  [:h2 {:style {:font-family "Georgia, 'Cormorant Garamond', serif"
                :font-size "clamp(1.4em, 3.2vw, 2em)"
                :color "#1b1a17"
                :text-align "center"
                :margin "0 0 0.8em"}}
   text])

(defn- home-step
  "One numbered step in the 'how it works' row."
  [n title body]
  [:div {:style {:flex "1 1 14em"
                 :min-width "12em"}}
   [:div {:style {:width "2.2em"
                  :height "2.2em"
                  :border-radius "50%"
                  :background "#b9770e"
                  :color "#fff"
                  :font-weight 700
                  :display "flex"
                  :align-items "center"
                  :justify-content "center"
                  :margin-bottom "0.6em"}}
    n]
   [:h3 {:style {:margin "0 0 0.3em"
                 :color "#1b1a17"
                 :font-size "1.05em"}}
    title]
   [:p {:style {:margin 0
                :color "#5c5648"
                :line-height "1.5"}}
    body]])

(defn- home-feature
  "One feature tile: an emoji glyph, a title and a one-liner."
  [glyph title body]
  [:div {:style {:background "#fff"
                 :border "1px solid #ece5d8"
                 :border-radius "0.6em"
                 :padding "1.1em"}}
   [:div {:style {:font-size "1.6em"
                  :margin-bottom "0.35em"}}
    glyph]
   [:h3 {:style {:margin "0 0 0.25em"
                 :font-size "1em"
                 :color "#1b1a17"}}
    title]
   [:p {:style {:margin 0
                :color "#5c5648"
                :font-size "0.92em"
                :line-height "1.5"}}
    body]])

(defn- pill
  "A small uppercase badge."
  [label bg]
  [:span {:style {:display "inline-block"
                  :font-size "0.62em"
                  :font-weight 700
                  :text-transform "uppercase"
                  :letter-spacing "0.06em"
                  :color "#fff"
                  :background bg
                  :padding "0.16em 0.55em"
                  :border-radius "0.28em"}}
   label])

(defn- decompose-illustration
  "Language-neutral graphic for 'formalize': a dense/tangled claim (dark uneven bars)
  resolving into three clean, numbered steps."
  []
  [:div {:style {:width "100%"}}
   (into [:div {:style {:background "#1b1a17"
                        :border-radius "0.5em"
                        :padding "0.85em 0.9em"}}]
         (for [w ["92%" "100%" "68%"]]
           ^{:key w}
           [:div {:style {:height "0.5em"
                          :width w
                          :background "#4a4436"
                          :border-radius "0.25em"
                          :margin-bottom "0.32em"}}]))
   [:div {:style {:text-align "center"
                  :color "#b9770e"
                  :font-size "1.3em"
                  :line-height "1.2"
                  :margin "0.2em 0"}}
    "↓"]
   (into [:div {:style {:display "flex"
                        :flex-direction "column"
                        :gap "0.4em"}}]
         (for [n [1 2 3]]
           ^{:key n}
           [:div {:style {:display "flex"
                          :align-items "center"
                          :gap "0.6em"
                          :background "#fff"
                          :border "1px solid #e2ddd2"
                          :border-radius "0.4em"
                          :padding "0.5em 0.7em"}}
            [:span {:style {:flex "0 0 1.5em"
                            :height "1.5em"
                            :border-radius "50%"
                            :background "#b9770e"
                            :color "#fff"
                            :font-weight 700
                            :font-size "0.8em"
                            :display "flex"
                            :align-items "center"
                            :justify-content "center"}}
             n]
            [:div {:style {:flex 1
                           :height "0.5em"
                           :background "#efe8da"
                           :border-radius "0.25em"}}]]))])

(defn- claim-anatomy
  "The worked example as a reasoning chain — Definition + Observation ⟹ Conclusion, plus
  the objection that keeps it honest. Bilingual copy from :home/ex-* / :home/tag-*."
  [lang]
  [:div {:style {:width "100%"}}
   (for [[k tag color] [[:home/ex-definition (i18n/t lang :home/tag-definition) "#a61e8c"]
                        [:home/ex-observation (i18n/t lang :home/tag-observation) "#0b7285"]
                        [:home/ex-conclusion (i18n/t lang :home/tag-conclusion) "#2b8a3e"]]]
     ^{:key k}
     [:div {:style {:background "#fff"
                    :border "1px solid #e2ddd2"
                    :border-radius "0.5em"
                    :padding "0.6em 0.8em"
                    :margin-bottom "0.5em"}}
      [pill tag color]
      [:p {:style {:margin "0.35em 0 0"
                   :color "#333"
                   :line-height "1.45"
                   :font-size "0.9em"}}
       (i18n/t lang k)]])
   [:div {:style {:border-left "3px solid #b9770e"
                  :background "#fdf6ec"
                  :padding "0.45em 0.7em"
                  :border-radius "0 0.4em 0.4em 0"
                  :color "#8a5709"
                  :font-style "italic"
                  :font-size "0.85em"}}
    (i18n/t lang :home/ex-objection)]])

(defn- prediction-example
  "One example prediction: a trigger glyph, a PREDICTION badge, the claim and how it resolves."
  [lang glyph claim resolves]
  [:div {:style {:flex "1 1 13em"
                 :background "#fff"
                 :border "1px solid #cfe0e3"
                 :border-left "4px solid #0b7285"
                 :border-radius "0.6em"
                 :padding "0.85em 1em"}}
   [:div {:style {:display "flex"
                  :align-items "center"
                  :gap "0.5em"
                  :margin-bottom "0.5em"}}
    [:span {:style {:font-size "1.25em"}}
     glyph]
    [pill (i18n/t lang :kind/prediction) "#0b7285"]]
   [:p {:style {:margin "0 0 0.5em"
                :color "#1b1a17"
                :font-weight 600
                :font-size "0.96em"
                :line-height "1.4"}}
    claim]
   [:p {:style {:margin 0
                :color "#5c5648"
                :font-size "0.84em"
                :line-height "1.5"}}
    resolves]])

(defn- prediction-pair
  "The two ways a prediction resolves: on a date, or on an event."
  [lang]
  [:div {:style {:display "flex"
                 :flex-wrap "wrap"
                 :gap "0.7em"}}
   [prediction-example
    lang
    "📅"
    (i18n/t lang :home/predict-date-claim)
    (i18n/t lang :home/predict-date-resolve)]
   [prediction-example
    lang
    "⚡"
    (i18n/t lang :home/predict-event-claim)
    (i18n/t lang :home/predict-event-resolve)]])

(defn- consensus-illustration
  "Graphic for 'find minds like yours': several contributors attest (✓) and converge on one
  shared step (labelled with the bilingual Conclusion tag)."
  [lang]
  [:div {:style {:display "flex"
                 :flex-direction "column"
                 :align-items "center"
                 :gap "0.55em"
                 :padding "0.4em 0"}}
   (into [:div {:style {:display "flex"
                        :gap "0.45em"}}]
         (for [[i c] (map-indexed vector ["#2b8a3e" "#0b7285" "#b9770e" "#2b8a3e"])]
           ^{:key i}
           [:div {:style {:width "1.9em"
                          :height "1.9em"
                          :border-radius "50%"
                          :background c
                          :color "#fff"
                          :display "flex"
                          :align-items "center"
                          :justify-content "center"
                          :font-size "0.95em"}}
            "✓"]))
   [:div {:style {:color "#b9770e"
                  :font-size "1.25em"}}
    "↓"]
   [:div {:style {:background "#fff"
                  :border "2px solid #2b8a3e"
                  :border-radius "0.5em"
                  :padding "0.55em 1em"}}
    [pill (i18n/t lang :home/tag-conclusion) "#2b8a3e"]]])

(defn- value-section
  "One value-prop detail: a centered heading, then an illustration beside its explanatory
  paragraph (stacking on narrow screens). `flip?` puts the illustration on the right."
  ([heading illustration body] (value-section heading illustration body false))
  ([heading illustration body flip?]
   (let [text [:p {:style {:flex "1 1 17em"
                           :min-width "14em"
                           :margin 0
                           :line-height "1.7"
                           :color "#5c5648"}}
               body]
         art [:div {:style {:flex "1 1 17em"
                            :min-width "14em"}}
              illustration]]
     [:section {:style {:margin "2.8em 0"}}
      [home-section-heading heading]
      (into [:div {:style {:display "flex"
                           :flex-wrap "wrap"
                           :gap "1.6em"
                           :align-items "center"
                           :justify-content "center"}}]
            (if flip? [text art] [art text]))])))

(defn landing-page
  "The Agora home/landing page (`/agora/<lang>`): a marketing hero, the four things Agora
  lets you do, how it works, a detail paragraph per value prop, and a closing call to
  action. The browse grid lives on the discover page."
  [_kis]
  (let [lang @(rf/subscribe [::i18n/lang])]
    [:div {:style {:max-width "60em"
                   :margin "1.5em auto"
                   :padding "0 0.9em"
                   :font-family "system-ui, sans-serif"}}
     [landing-hero lang]
     ;; What Agora lets you do — four value props (the problem it solves)
     [:section {:style {:margin "2.6em 0"}}
      [home-section-heading (i18n/t lang :home/value-title)]
      (into
       [:div {:style {:display "grid"
                      :grid-template-columns "repeat(auto-fit, minmax(min(16em, 100%), 1fr))"
                      :gap "0.9em"}}]
       [[home-feature "✍️" (i18n/t lang :home/value-1-title) (i18n/t lang :home/value-1-body)]
        [home-feature "🧠" (i18n/t lang :home/value-2-title) (i18n/t lang :home/value-2-body)]
        [home-feature "🔮" (i18n/t lang :home/value-3-title) (i18n/t lang :home/value-3-body)]
        [:div]
        [home-feature "🤝" (i18n/t lang :home/value-4-title) (i18n/t lang :home/value-4-body)]])]
     ;; How it works — three steps
     [:section {:style {:margin "2.6em 0"}}
      [home-section-heading (i18n/t lang :home/how-title)]
      [:div {:style {:display "flex"
                     :flex-wrap "wrap"
                     :gap "1.4em"}}
       [home-step "1" (i18n/t lang :home/how-1-title) (i18n/t lang :home/how-1-body)]
       [home-step "2" (i18n/t lang :home/how-2-title) (i18n/t lang :home/how-2-body)]
       [home-step "3" (i18n/t lang :home/how-3-title) (i18n/t lang :home/how-3-body)]]]
     ;; One illustrated detail per value prop, illustration side alternating.
     ;; value-2 reuses the problem framing + the worked example; value-3 the prediction lead
     ;; + cards; value-1/4 get language-neutral graphics.
     [value-section
      (i18n/t lang :home/value-1-title)
      [decompose-illustration]
      (i18n/t lang :home/value-1-lead)]
     [value-section
      (i18n/t lang :home/value-2-title)
      [claim-anatomy lang]
      (i18n/t lang :home/problem-body)
      true]
     [value-section
      (i18n/t lang :home/value-3-title)
      [prediction-pair lang]
      (i18n/t lang :home/predict-lead)]
     [value-section
      (i18n/t lang :home/value-4-title)
      [consensus-illustration lang]
      (i18n/t lang :home/value-4-lead)
      true]
     ;; Closing call to action
     [:section {:style {:background "linear-gradient(160deg, #b9770e, #8a5709)"
                        :color "#fff"
                        :border-radius "0.8em"
                        :padding "2.4em 1.6em"
                        :margin "2.6em 0 1em"
                        :text-align "center"}}
      [:h2 {:style {:font-family "Georgia, 'Cormorant Garamond', serif"
                    :font-size "clamp(1.4em, 3.4vw, 2em)"
                    :margin "0 0 0.5em"}}
       (i18n/t lang :home/cta-title)]
      [:p {:style {:max-width "36em"
                   :margin "0 auto 1.3em"
                   :line-height "1.6"
                   :color "#fbe6cf"}}
       (i18n/t lang :home/cta-body)]
      [:div {:style {:display "flex"
                     :flex-wrap "wrap"
                     :gap "0.7em"
                     :justify-content "center"}}
       [:a {:href (i18n/new-ki lang)
            :style {:padding "0.7em 1.5em"
                    :background "#1b1a17"
                    :color "#f0e6d2"
                    :border-radius "0.4em"
                    :font-weight 600
                    :text-decoration "none"}}
        (i18n/t lang :home/cta-publish)]
       [:a {:href (i18n/discover lang)
            :style {:padding "0.7em 1.5em"
                    :background "transparent"
                    :color "#fff"
                    :border "1px solid rgba(255,255,255,0.7)"
                    :border-radius "0.4em"
                    :text-decoration "none"}}
        (i18n/t lang :home/cta-explore)]]]]))

