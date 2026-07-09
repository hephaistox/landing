(ns landing.agora.frontend.landing
  "The Agora landing/home page (`/agora/<lang>`): marketing hero, the illustrated
  'anatomy of a claim', the pain it solves, how it works, a feature grid, the prediction
  teaser, a live example KI, and a closing call to action."
  (:require
   [landing.agora.frontend.cite          :as cite]
   [landing.agora.frontend.document-view :as    dv
                                         :refer [kind-badge permalink]]
   [landing.agora.frontend.i18n          :as i18n]
   [re-frame.core                        :as rf]))

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
                  :color "#8f8776"}}
    (i18n/t lang :home/trust)]])

(defn- landing-spotlight
  "A live example — one real KI from the graph, rendered prominently so a first-time
  visitor sees what a Knowledge Item is."
  [lang k]
  [:a {:href (permalink lang k)
       :style {:display "block"
               :border "1px solid #e2ddd2"
               :border-left "4px solid #b9770e"
               :border-radius "0.6em"
               :background "#fff"
               :padding "1.1em 1.3em"
               :margin-bottom "1.6em"
               :text-decoration "none"
               :color "inherit"}}
   [:div {:style {:font-size "0.72em"
                  :text-transform "uppercase"
                  :letter-spacing "0.06em"
                  :color "#b9770e"
                  :margin-bottom "0.55em"}}
    (i18n/t lang :landing/example-label)]
   [:div {:style {:display "flex"
                  :align-items "center"
                  :gap "0.6em"
                  :margin-bottom "0.45em"}}
    [kind-badge (:kind k)]
    [:span {:style {:font-weight 700
                    :font-size "1.15em"}}
     (or (:title k) (:name k))]]
   [:p {:style {:margin "0 0 0.5em"
                :color "#333"
                :line-height "1.55"}}
    (cite/node-text k)]
   [:span {:style {:color "#b9770e"
                   :font-weight 600
                   :font-size "0.9em"}}
    (i18n/t lang :landing/explore)]])

(defn- home-section-heading
  "Serif section title, centered — the recurring divider between landing sections."
  [text]
  [:h2 {:style {:font-family "Georgia, 'Cormorant Garamond', serif"
                :font-size "clamp(1.4em, 3.2vw, 2em)"
                :color "#1b1a17"
                :text-align "center"
                :margin "0 0 0.8em"}}
   text])

(defn- home-mini-node
  "One illustrative card in the reasoning diagram: a coloured type tag over its text.
  Pure illustration (not live data), so the labels are plain words, not KI kinds."
  [tag color text]
  [:div {:style {:flex "1 1 15em"
                 :max-width "22em"
                 :background "#fff"
                 :border "1px solid #e6e0d4"
                 :border-left (str "4px solid " color)
                 :border-radius "0.55em"
                 :padding "0.85em 1em"
                 :box-shadow "0 1px 3px rgba(0,0,0,0.06)"
                 :text-align "left"}}
   [:div {:style {:font-size "0.66em"
                  :font-weight 700
                  :text-transform "uppercase"
                  :letter-spacing "0.09em"
                  :color color
                  :margin-bottom "0.35em"}}
    tag]
   [:div {:style {:color "#2a2621"
                  :line-height "1.45"}}
    text]])



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

(defn- prediction-example
  "One illustrated example prediction: a trigger glyph, a PREDICTION badge, the claim,
  and a line on how/when it resolves. Copy-only teaser — no evaluation behind it yet."
  [glyph claim resolves]
  [:div {:style {:flex "1 1 16em"
                 :background "#fff"
                 :border "1px solid #cfe0e3"
                 :border-left "4px solid #0b7285"
                 :border-radius "0.6em"
                 :padding "1.1em 1.2em"}}
   [:div {:style {:display "flex"
                  :align-items "center"
                  :gap "0.5em"
                  :margin-bottom "0.55em"}}
    [:span {:style {:font-size "1.35em"}}
     glyph]
    [kind-badge "prediction"]]
   [:p {:style {:margin "0 0 0.6em"
                :color "#1b1a17"
                :font-weight 600
                :font-size "1.02em"
                :line-height "1.4"}}
    claim]
   [:p {:style {:margin 0
                :color "#5c5648"
                :font-size "0.88em"
                :line-height "1.5"}}
    resolves]])

(defn- prediction-teaser
  "The prediction pitch: a lead, two example predictions (date-triggered vs
  event-triggered — the two ways a claim resolves), and a note on settlement."
  [lang]
  [:div
   [:p {:style {:max-width "40em"
                :margin "0 auto 1.4em"
                :text-align "center"
                :color "#5c5648"
                :line-height "1.6"}}
    (i18n/t lang :home/predict-lead)]
   [:div {:style {:display "flex"
                  :flex-wrap "wrap"
                  :gap "1em"
                  :justify-content "center"}}
    [prediction-example
     "📅"
     (i18n/t lang :home/predict-date-claim)
     (i18n/t lang :home/predict-date-resolve)]
    [prediction-example
     "⚡"
     (i18n/t lang :home/predict-event-claim)
     (i18n/t lang :home/predict-event-resolve)]]
   [:p {:style {:max-width "40em"
                :margin "1.4em auto 0"
                :text-align "center"
                :color "#8a7a55"
                :font-size "0.9em"
                :font-style "italic"}}
    (i18n/t lang :home/predict-footer)]])

(defn landing-page
  "The Agora home/landing page (`/agora/<lang>`): a marketing hero, an illustrated
  'anatomy of a claim', the pain it solves, how it works, a feature grid, the
  prediction pitch, a live example KI from the graph, and a closing call to action.
  The full browse grid lives on the discover page."
  [kis]
  (let [lang @(rf/subscribe [::i18n/lang])]
    [:div {:style {:max-width "60em"
                   :margin "1.5em auto"
                   :padding "0 0.9em"
                   :font-family "system-ui, sans-serif"}}
     [landing-hero lang]
     ;; Predictions — put a claim on the record
     [:section {:style {:margin "2.6em 0"}}
      [home-section-heading (i18n/t lang :home/predict-title)]
      [prediction-teaser lang]]
     ;; The pain it solves
     [:section {:style {:background "#1b1a17"
                        :color "#e8e2d6"
                        :border-radius "0.8em"
                        :padding "2.2em 1.6em"
                        :margin "2.6em 0"
                        :text-align "center"}}
      [:h2 {:style {:font-family "Georgia, 'Cormorant Garamond', serif"
                    :font-size "clamp(1.4em, 3.4vw, 2em)"
                    :margin "0 0 0.55em"
                    :color "#f0e6d2"}}
       (i18n/t lang :home/problem-title)]
      [:p {:style {:max-width "40em"
                   :margin "0 auto"
                   :line-height "1.7"
                   :color "#c9c1b2"}}
       (i18n/t lang :home/problem-body)]]
     ;; How it works — three steps
     [:section {:style {:margin "2.6em 0"}}
      [home-section-heading (i18n/t lang :home/how-title)]
      [:div {:style {:display "flex"
                     :flex-wrap "wrap"
                     :gap "1.4em"}}
       [home-step "1" (i18n/t lang :home/how-1-title) (i18n/t lang :home/how-1-body)]
       [home-step "2" (i18n/t lang :home/how-2-title) (i18n/t lang :home/how-2-body)]
       [home-step "3" (i18n/t lang :home/how-3-title) (i18n/t lang :home/how-3-body)]]]
     ;; Feature grid
     [:section {:style {:margin "2.6em 0"}}
      [home-section-heading (i18n/t lang :home/features-title)]
      (into
       [:div {:style {:display "grid"
                      :grid-template-columns "repeat(auto-fit, minmax(min(16em, 100%), 1fr))"
                      :gap "0.9em"}}]
       [[home-feature "🔗" (i18n/t lang :home/feat-terms-title) (i18n/t lang :home/feat-terms-body)]
        [home-feature
         "⚔️"
         (i18n/t lang :home/feat-objection-title)
         (i18n/t lang :home/feat-objection-body)]
        [home-feature
         "🌳"
         (i18n/t lang :home/feat-versions-title)
         (i18n/t lang :home/feat-versions-body)]
        [home-feature "🕒" (i18n/t lang :home/feat-time-title) (i18n/t lang :home/feat-time-body)]
        [home-feature
         "📉"
         (i18n/t lang :home/feat-confidence-title)
         (i18n/t lang :home/feat-confidence-body)]
        [home-feature
         "🌍"
         (i18n/t lang :home/feat-lang-title)
         (i18n/t lang :home/feat-lang-body)]])]
     ;; A real KI from the graph
     (when-let [k (first kis)]
       [:section {:style {:margin "2.6em 0"}}
        [home-section-heading (i18n/t lang :home/live-title)]
        [landing-spotlight lang k]])
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

