(ns landing.agora.frontend.landing
  "The Agora landing/home page (`/agora/<lang>`): focused on what a reader can do *now*.
  A pitch box with a single explore-the-articles action, then one **living example** — the same
  article (« Ma voiture est rapide ») improving across four real published editions (the
  `exemple-pedagogique-1..4` publications). Each edition is its own panel, two columns: the real
  publication graph (left) and the article's prose at that edition (right), plus the skeptical
  objection that drove the next one. It closes on « what this changes » for a reader. Everything is
  real seeded data, fetched live. The mechanism detail lives in the FAQ."
  (:require
   [landing.agora.document.identity :as di]
   [landing.agora.frontend.graph    :as graph]
   [landing.agora.frontend.i18n     :as i18n]
   [re-frame.core                   :as rf]
   [reagent.core                    :as r]))

;; --- data: the four editions (real publications) ----------------------------

(def ^:private stages
  "The pedagogical editions, in order — each a real published publication whose graph we show."
  [1 2 3 4])

(def ^:private stage-objection
  "The skeptic's objection shown under an edition — the one that drives the next edition."
  {1 :home/live-q1
   2 :home/live-q2
   3 :home/live-q4})

(rf/reg-sub ::pedago (fn [db _] (:agora/pedago db)))
(rf/reg-event-db ::noop (fn [db _] db))

(rf/reg-event-db ::text-loaded
                 (fn [db [_ n resp]] (assoc-in db [:agora/pedago n :article] (:body resp))))

(rf/reg-event-fx ::graph-loaded
                 (fn [{:keys [db]} [_ n resp]]
                   (let [lang (or (::i18n/lang db) "fr")
                         graph (:body resp)
                         art (first (filter #(and (= "article" (:type %)) (= lang (:lang %)))
                                            (:nodes graph)))]
                     (cond-> {:db (assoc-in db [:agora/pedago n :graph] graph)}
                       art (assoc :fetch
                                  {:method :get
                                   :url (str "/agora/api/documents/article/" (:id art))
                                   :headers {"Accept" "application/json"}
                                   :response-content-types {#"application/json" :json}
                                   :on-success [::text-loaded n]
                                   :on-failure [::noop]})))))

(rf/reg-event-fx ::load
                 (fn [_ _]
                   {:fetch (mapv (fn [n]
                                   {:method :get
                                    :url
                                    (str "/agora/api/publication/exemple-pedagogique-" n "/graph")
                                    :headers {"Accept" "application/json"}
                                    :response-content-types {#"application/json" :json}
                                    :on-success [::graph-loaded n]
                                    :on-failure [::noop]})
                                 stages)}))

;; --- pieces -----------------------------------------------------------------

(defn- landing-hero
  "The pitch box: what Agora is, and the one action — explore the articles."
  [lang]
  [:section {:style {:background "linear-gradient(160deg, #1b1a17, #262019)"
                     :color "#e8e2d6"
                     :border-radius "0.8em"
                     :padding "2.6em 1.4em"
                     :margin-bottom "2em"
                     :text-align "center"}}
   [:h1 {:style {:font-family "Georgia, 'Cormorant Garamond', serif"
                 :font-size "clamp(1.7em, 4.4vw, 2.7em)"
                 :line-height 1.16
                 :margin "0 0 0.5em"
                 :color "#f0e6d2"}}
    (i18n/t lang :home/headline)]
   [:p {:style {:max-width "40em"
                :margin "0 auto 1.5em"
                :font-size "1.08em"
                :line-height 1.6
                :color "#c9c1b2"}}
    (i18n/t lang :home/subtitle)]
   [:a {:href (i18n/articles lang)
        :style {:display "inline-block"
                :padding "0.7em 1.6em"
                :background "#b9770e"
                :color "#fff"
                :border-radius "0.4em"
                :font-weight 600
                :text-decoration "none"}}
    (i18n/t lang :home/explore-cta)]])

(defn- section-heading
  "Serif section title with an eyebrow above it — a section opens on this, not on prose."
  [eyebrow title]
  [:div {:style {:margin "0 0 1.4em"}}
   (when eyebrow
     [:div {:style {:font-size "0.75em"
                    :font-weight 700
                    :text-transform "uppercase"
                    :letter-spacing "0.14em"
                    :color "#b9770e"
                    :margin-bottom "0.35em"}}
      eyebrow])
   [:h2 {:style {:font-family "Georgia, 'Cormorant Garamond', serif"
                 :font-size "clamp(1.4em, 3.2vw, 2em)"
                 :color "#1b1a17"
                 :margin 0}}
    title]])

(defn- edition-objection
  "Under an edition's article: the skeptic's objection that drove the next edition (none on the last)."
  [lang n]
  (when-let [obj (stage-objection n)]
    [:div {:style {:margin "1em 0 0"}}
     [:div {:style {:display "inline-block"
                    :background "#faf5ea"
                    :border "1px solid #e6d9bd"
                    :border-radius "1em 1em 1em 0.2em"
                    :padding "0.55em 0.9em"
                    :font-style "italic"
                    :color "#7a5a12"
                    :line-height 1.4}}
      (str "🧐  " (i18n/t lang obj))]]))

(defn- edition-arrow
  "A big, graphic connector between two editions — the article visibly moving forward a version."
  []
  [:div {:style {:display "flex"
                 :justify-content "center"
                 :margin "1.1em 0"}}
   [:svg {:width "56"
          :height "72"
          :viewBox "0 0 56 72"
          :aria-hidden "true"}
    [:defs
     [:linearGradient {:id "agora-edition-arrow"
                       :x1 "0"
                       :y1 "0"
                       :x2 "0"
                       :y2 "1"}
      [:stop {:offset "0%"
              :stop-color "#d99a2b"}]
      [:stop {:offset "100%"
              :stop-color "#b9770e"}]]]
    [:path {:d "M20 0 h16 v40 h14 L28 72 L6 40 h14 z"
            :fill "url(#agora-edition-arrow)"}]]])

(defn- edition-block
  "One edition, two columns and no frame: the real publication graph (left) and the article's prose at
  this edition (right), then the objection that drove the next one."
  [lang n data]
  (let [nodes (filter #(= lang (:lang %)) (get-in data [:graph :nodes]))
        ids (into #{} (map :id) nodes)
        edges (filterv #(and (ids (:from %)) (ids (:to %))) (get-in data [:graph :edges]))
        article (:article data)]
    [:div {:style {:display "flex"
                   :flex-wrap "wrap"
                   :gap "1.2em"
                   :align-items "stretch"}}
     [:div {:style {:flex "1 1 21em"
                    :min-width "16em"}}
      (when (seq nodes)
        ^{:key (count nodes)} [graph/graph-canvas lang nodes edges {:height "17em"}])]
     [:div {:style {:flex "1 1 18em"
                    :min-width "15em"
                    :display "flex"
                    :flex-direction "column"}}
      (if article
        [:p {:style {:margin 0
                     :font-size "1.04em"
                     :line-height 1.65
                     :color "#2a2723"}}
         (di/plain-text (:text article) (:cite-titles article))]
        [:div {:style {:height "3em"}}])
      [edition-objection lang n]]]))

(defn- living-example
  "The living example: one article improving across four real editions, each shown two-up — its real
  publication graph beside the article's prose at that edition — a ↓ marking each move forward."
  [lang]
  (let [pedago @(rf/subscribe [::pedago])]
    [:section {:style {:margin "3.2em 0"
                       :padding "2em 1.6em"
                       :background "#f7f2e8"
                       :border "1px solid #ece0c8"
                       :border-radius "0.9em"}}
     [section-heading (i18n/t lang :home/live-eyebrow) (i18n/t lang :home/live-title)]
     (for [n stages]
       ^{:key n}
       [:div [edition-block lang n (get pedago n)] (when (< n (last stages)) [edition-arrow])])]))

(defn- soon-card
  "One forward-looking capability in the « à venir » band."
  [title body]
  [:div {:style {:flex "1 1 14em"
                 :min-width "12em"
                 :background "#fff"
                 :border "1px solid #e2ddd2"
                 :border-radius "0.6em"
                 :padding "1.1em 1.2em"}}
   [:h3 {:style {:margin "0 0 0.35em"
                 :font-size "1.02em"
                 :color "#1b1a17"}}
    title]
   [:p {:style {:margin 0
                :color "#5c5648"
                :font-size "0.92em"
                :line-height 1.55}}
    body]])

(defn- soon-section
  "« À venir » — what a reader will soon be able to do: answer the author, read a dated prediction the
  world will settle, and declare which premises they accept to find articles that complete their view."
  [lang]
  [:section {:style {:margin "3.2em 0"
                     :padding "2em 1.6em"
                     :background "#f4f0ea"
                     :border "1px dashed #cbb98f"
                     :border-radius "0.9em"}}
   [section-heading (i18n/t lang :home/live-soon) (i18n/t lang :home/soon-title)]
   [:div {:style {:display "flex"
                  :flex-wrap "wrap"
                  :gap "0.9em"}}
    [soon-card (i18n/t lang :home/soon-1-title) (i18n/t lang :home/live-close)]
    [soon-card (i18n/t lang :home/soon-2-title) (i18n/t lang :home/soon-2-body)]
    [soon-card (i18n/t lang :home/soon-3-title) (i18n/t lang :home/soon-3-body)]]])

(defn- cta-section
  "The closing call to action — go find an article that speaks to you."
  [lang]
  [:section {:style {:margin "3.2em 0 1em"
                     :text-align "center"}}
   [:h2 {:style {:font-family "Georgia, 'Cormorant Garamond', serif"
                 :font-size "clamp(1.4em, 3.4vw, 2.1em)"
                 :color "#1b1a17"
                 :margin "0 0 0.9em"}}
    (i18n/t lang :home/find-title)]
   [:a {:href (i18n/articles lang)
        :style {:display "inline-block"
                :padding "0.75em 1.7em"
                :background "#b9770e"
                :color "#fff"
                :border-radius "0.4em"
                :font-weight 600
                :font-size "1.05em"
                :text-decoration "none"}}
    (i18n/t lang :home/find-cta)]])

(defn- change-section
  "« What this changes » — the reader-facing payoff: you stay in touch with the author, and you see
  what others really think far better than in social-media comments, above all when it moves the
  article forward."
  [lang]
  [:section {:style {:margin "0 0 1em"
                     :padding "2em 1.6em"
                     :background "#1b1a17"
                     :color "#e8e2d6"
                     :border-radius "0.9em"}}
   [:h2 {:style {:font-family "Georgia, 'Cormorant Garamond', serif"
                 :font-size "clamp(1.4em, 3.2vw, 2em)"
                 :color "#f0e6d2"
                 :margin "0 0 0.6em"}}
    (i18n/t lang :home/change-title)]
   [:p {:style {:max-width "44em"
                :margin 0
                :line-height 1.75
                :font-size "1.05em"
                :color "#c9c1b2"}}
    (i18n/t lang :home/change-body)]])

(defn landing-page
  "The Agora home/landing page (`/agora/<lang>`). Fetches the four editions' real graphs on mount,
  then reads reader-first: the pitch box (explore-the-articles), the living example (one article
  improving across four editions, graph beside prose), and « what this changes » for a reader."
  [_arg]
  (r/with-let [_ (rf/dispatch [::load])]
              (let [lang @(rf/subscribe [::i18n/lang])]
                [:div {:style {:max-width "60em"
                               :margin "1.5em auto"
                               :padding "0 0.9em"
                               :font-family "system-ui, sans-serif"}}
                 [landing-hero lang]
                 [living-example lang]
                 [soon-section lang]
                 [change-section lang]
                 [cta-section lang]])))
