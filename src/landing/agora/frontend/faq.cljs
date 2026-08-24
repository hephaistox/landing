(ns landing.agora.frontend.faq
  "The Agora technical FAQ (`/agora/<lang>/faq`): a plain-language, question-by-question explanation of
  how the graph works — kept **off** the landing (too much for a first visit) and reached from the
  footer. Each mechanism is shown with **real Agora cards**, not mockups: a small seeded cluster (a
  definition, a measurable fact, a deduction citing both, an induction on top) rendered through the
  same `discover-card` the app uses, so the explanation is real and alive."
  (:require
   [landing.agora.frontend.document-page :as dv]
   [landing.agora.frontend.i18n          :as i18n]
   [re-frame.core                        :as rf]
   [reagent.core                         :as r]))

;; --- live example cards -----------------------------------------------------

(def ^:private example-cids
  "The seeded example cluster, by stable cid (see the `exemples-landing` publication)."
  ["ex-rapide" "ex-chrono" "ex-deduction" "ex-induction"])

(rf/reg-sub ::examples (fn [db _] (:agora/faq-examples db)))
(rf/reg-event-db ::noop (fn [db _] db))
(rf/reg-event-db ::example-loaded
                 (fn [db [_ resp]]
                   (let [d (:body resp)]
                     (cond-> db
                       (:name d) (assoc-in [:agora/faq-examples (:name d)] d)))))
(rf/reg-event-fx ::load-examples
                 (fn [{:keys [db]} _]
                   (let [lang (or (::i18n/lang db) "fr")]
                     {:fetch (mapv (fn [cid]
                                     {:method :get
                                      :url (str "/agora/api/documents/ki/" cid "/" lang "/1")
                                      :headers {"Accept" "application/json"}
                                      :response-content-types {#"application/json" :json}
                                      :on-success [::example-loaded]
                                      :on-failure [::noop]})
                                   example-cids)})))

(defn- example-card
  "The real `discover-card` for a seeded example (by cid), or a placeholder while it loads."
  [lang cid]
  (if-let [node (get @(rf/subscribe [::examples]) cid)]
    [dv/discover-card lang node]
    [:div {:style {:min-height "11em"
                   :border "1px dashed #e2ddd2"
                   :border-radius "0.6em"
                   :background "#faf8f3"}}]))

(defn- pair
  "Two cards side by side (stacking on narrow screens)."
  [& cards]
  (into [:div {:style {:display "grid"
                       :grid-template-columns "repeat(auto-fit, minmax(min(17em, 100%), 1fr))"
                       :gap "0.9em"
                       :margin "0.9em 0 0.4em"}}]
        cards))

(defn- chip
  "A small coloured pill standing in for a badge on a not-yet-built kind (objection, detection)."
  [color label]
  [:span {:style {:display "inline-block"
                  :background color
                  :color "#fff"
                  :font-size "0.7em"
                  :font-weight 700
                  :letter-spacing "0.05em"
                  :text-transform "uppercase"
                  :padding "0.2em 0.6em"
                  :border-radius "0.25em"}}
   label])

(defn- mock-card
  "A card that looks real but stands for a not-yet-built mechanism (the « À venir » block)."
  [badge title body]
  [:div {:style {:display "flex"
                 :flex-direction "column"
                 :gap "0.55em"
                 :min-height "10em"
                 :padding "0.9em 1em"
                 :border "1px solid #e2ddd2"
                 :border-radius "0.6em"
                 :background "#fff"
                 :box-shadow "0 1px 3px rgba(0,0,0,0.06)"}}
   [:div {:style {:display "flex"
                  :align-items "center"
                  :gap "0.5em"}}
    badge]
   [:div {:style {:font-weight 700
                  :font-size "1.02em"
                  :color "#2a2723"}}
    title]
   [:div {:style {:font-size "0.9em"
                  :line-height 1.4
                  :color "#555"}}
    body]])

;; --- Q&A --------------------------------------------------------------------

(defn- qa
  "One FAQ entry: a question heading, a plain-language answer, then optional illustration hiccup."
  [question answer & illustration]
  [:section {:style {:margin "2.2em 0"}}
   [:h2 {:style {:font-family "Georgia, 'Cormorant Garamond', serif"
                 :font-size "1.35em"
                 :color "#1b1a17"
                 :margin "0 0 0.5em"}}
    question]
   [:p {:style {:line-height 1.6
                :color "#4a4640"
                :margin "0 0 0.4em"
                :max-width "46em"}}
    answer]
   (into [:div] illustration)])

(defn faq-page
  "The technical FAQ page. Fetches the example cluster on mount and answers, one question at a time,
  how the graph works — each mechanism illustrated with real cards."
  [_arg]
  (r/with-let
   [_ (rf/dispatch [::load-examples])]
   (let [lang @(rf/subscribe [::i18n/lang])
         t #(i18n/t lang %)]
     [:div {:style {:max-width "52em"
                    :margin "1.6em auto"
                    :padding "0 0.9em"
                    :font-family "system-ui, sans-serif"}}
      [:h1 {:style {:font-family "Georgia, 'Cormorant Garamond', serif"
                    :font-size "clamp(1.7em, 4vw, 2.4em)"
                    :color "#1b1a17"
                    :margin "0 0 0.2em"}}
       (t :faq/title)]
      [:p {:style {:color "#6b6456"
                   :line-height 1.6
                   :margin "0 0 1.6em"}}
       (t :faq/lead)]
      [qa (t :faq/q-ki) (t :faq/a-ki)]
      [qa
       (t :faq/q-status)
       (t :faq/a-status)
       [pair [example-card lang "ex-rapide"] [example-card lang "ex-deduction"]]]
      [qa
       (t :faq/q-leap)
       (t :faq/a-leap)
       [pair [example-card lang "ex-deduction"] [example-card lang "ex-induction"]]]
      [qa (t :faq/q-localize) (t :faq/a-localize)]
      [:section {:style {:margin "2.6em 0"
                         :padding "1.8em 1.4em"
                         :background "#f4f0ea"
                         :border "1px dashed #cbb98f"
                         :border-radius "0.9em"}}
       [:div {:style {:font-size "0.78em"
                      :font-weight 700
                      :text-transform "uppercase"
                      :letter-spacing "0.14em"
                      :color "#b9770e"
                      :margin-bottom "0.6em"}}
        (t :home/upcoming-eyebrow)]
       [:h2 {:style {:font-family "Georgia, 'Cormorant Garamond', serif"
                     :font-size "1.35em"
                     :color "#1b1a17"
                     :margin "0 0 0.5em"}}
        (t :home/upcoming-title)]
       [:p {:style {:line-height 1.6
                    :color "#4a4640"
                    :margin "0 0 0.9em"}}
        (t :home/upcoming-lead)]
       [:div {:style {:display "grid"
                      :grid-template-columns "repeat(auto-fit, minmax(min(15em, 100%), 1fr))"
                      :gap "0.9em"}}
        [mock-card
         [chip "#e03131" (t :home/mock-objection-badge)]
         (t :home/mock-objection-title)
         (t :home/mock-objection-body)]
        [mock-card
         [chip "#0b7285" (t :home/mock-detection-badge)]
         (t :home/mock-detection-title)
         (t :home/mock-detection-body)]
        [mock-card
         [dv/kind-badge :prediction]
         (t :home/mock-prediction-title)
         (t :home/mock-prediction-body)]]]])))
