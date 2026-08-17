(ns landing.agora.frontend.beta
  "The Agora beta notice page (`/agora/<lang>/beta`): says plainly that Agora is in beta — the
  founding concepts are in place, but the interface still asks the reader to understand how Agora
  works — and lays out the three-step roadmap (read → challenge → write). Reached from the « Bêta »
  badge shown in the header on every page."
  (:require
   [landing.agora.frontend.i18n :as i18n]
   [re-frame.core               :as rf]))

(defn- step
  "One roadmap step: a numbered disc, a title and a one-line body."
  [n title body]
  [:div {:style {:display "flex"
                 :gap "0.9em"
                 :align-items "flex-start"}}
   [:div {:style {:flex "0 0 2.2em"
                  :width "2.2em"
                  :height "2.2em"
                  :border-radius "50%"
                  :background "#b9770e"
                  :color "#fff"
                  :font-weight 700
                  :display "flex"
                  :align-items "center"
                  :justify-content "center"}}
    n]
   [:div
    [:h3 {:style {:margin "0 0 0.2em"
                  :font-size "1.05em"
                  :color "#1b1a17"}}
     title]
    [:p {:style {:margin 0
                 :color "#5c5648"
                 :line-height 1.55}}
     body]]])

(defn beta-page
  "The beta notice + roadmap page."
  [_arg]
  (let [lang @(rf/subscribe [::i18n/lang])]
    [:div {:style {:max-width "48em"
                   :margin "1.6em auto"
                   :padding "0 0.9em"
                   :font-family "system-ui, sans-serif"}}
     [:div {:style {:display "inline-block"
                    :font-size "0.72em"
                    :font-weight 700
                    :letter-spacing "0.1em"
                    :text-transform "uppercase"
                    :color "#1b1a17"
                    :background "#d99a2b"
                    :padding "0.2em 0.6em"
                    :border-radius "0.25em"
                    :margin-bottom "0.6em"}}
      (i18n/t lang :beta/badge)]
     [:h1 {:style {:font-family "Georgia, 'Cormorant Garamond', serif"
                   :font-size "clamp(1.7em, 4vw, 2.4em)"
                   :color "#1b1a17"
                   :margin "0 0 0.4em"}}
      (i18n/t lang :beta/title)]
     [:p {:style {:color "#4a4640"
                  :line-height 1.7
                  :font-size "1.05em"
                  :margin "0 0 1.8em"
                  :max-width "40em"}}
      (i18n/t lang :beta/intro)]
     [:div {:style {:display "flex"
                    :flex-direction "column"
                    :gap "1.3em"}}
      [step "1" (i18n/t lang :beta/step-1-title) (i18n/t lang :beta/step-1-body)]
      [step "2" (i18n/t lang :beta/step-2-title) (i18n/t lang :beta/step-2-body)]
      [step "3" (i18n/t lang :beta/step-3-title) (i18n/t lang :beta/step-3-body)]]]))
