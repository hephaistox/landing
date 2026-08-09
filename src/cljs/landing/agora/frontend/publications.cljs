(ns landing.agora.frontend.publications
  "Publications — the work-packages you author in."
  (:require
   [clojure.string                       :as str]
   [landing.agora.frontend.auth          :as auth]
   [landing.agora.frontend.document-page :as dv]
   [landing.agora.frontend.i18n          :as i18n]
   [re-frame.core                        :as rf]
   [reagent.core                         :as r]
   [superstructor.re-frame.fetch-fx]))

;; --- subs ------------------------------------------------------------------

(rf/reg-sub ::results (fn [db _] (:agora/publication-results db)))
(rf/reg-sub ::viewed (fn [db _] (:agora/viewed-publication db)))
(rf/reg-sub ::viewed-cards (fn [db _] (:agora/viewed-publication-cards db)))

;; --- events ----------------------------------------------------------------

(defn- GET
  [url ok fail]
  {:method :get
   :url url
   :headers {"Accept" "application/json"}
   :response-content-types {#"application/json" :json}
   :on-success ok
   :on-failure fail})

;; the caller's open (draft) publications — the index list, optionally filtered by title `q`
(rf/reg-event-fx ::search
                 (fn [_ [_ q]]
                   {:fetch (GET (str "/agora/api/publication?q=" (js/encodeURIComponent (or q "")))
                                [::search-ok]
                                [::search-fail])}))
(rf/reg-event-db ::search-ok (fn [db [_ resp]] (assoc db :agora/publication-results (:body resp))))
(rf/reg-event-db ::search-fail (fn [db _] (assoc db :agora/publication-results [])))

(rf/reg-event-fx ::create
                 (fn [{:keys [db]} [_ title]]
                   {:db (assoc db :agora/publication-creating? true)
                    :fetch {:method :post
                            :url "/agora/api/publication"
                            :headers {"Content-Type" "application/json"
                                      "Accept" "application/json"}
                            :body (js/JSON.stringify (clj->js {:title title}))
                            :response-content-types {#"application/json" :json}
                            :on-success [::created]
                            :on-failure [::create-failed]}}))
;; on create, go straight to the new publication's page (its documents live there); refresh the index
(rf/reg-event-fx ::created
                 (fn [{:keys [db]} [_ resp]]
                   (let [id (:id (:body resp))]
                     {:db (dissoc db :agora/publication-creating?)
                      :dispatch [::search]
                      :agora/navigate (i18n/publication (i18n/current db) id)})))
(rf/reg-event-db ::create-failed (fn [db _] (dissoc db :agora/publication-creating?)))

;; the publication page's data (the publication + its documents as discover cards)
(rf/reg-event-fx
 ::load-page
 (fn [{:keys [db]} [_ id]]
   {:db (assoc db :agora/viewed-publication {:id id} :agora/viewed-publication-cards nil)
    :fetch (GET (str "/agora/api/publication/" id) [::page-ok] [::page-fail])
    :dispatch [::load-page-cards id]}))
(rf/reg-event-db ::page-ok (fn [db [_ resp]] (assoc db :agora/viewed-publication (:body resp))))
(rf/reg-event-db ::page-fail (fn [db _] db))
(rf/reg-event-fx ::load-page-cards
                 (fn [_ [_ id]]
                   {:fetch (GET (str "/agora/api/publication/" id "/documents")
                                [::page-cards-ok]
                                [::page-cards-fail])}))
(rf/reg-event-db ::page-cards-ok
                 (fn [db [_ resp]] (assoc db :agora/viewed-publication-cards (:body resp))))
(rf/reg-event-db ::page-cards-fail (fn [db _] (assoc db :agora/viewed-publication-cards [])))

;; rename a publication → new minor (owner only, server-checked); refresh the page + the index
(rf/reg-event-fx ::rename
                 (fn [_ [_ cid title]]
                   {:fetch {:method :put
                            :url (str "/agora/api/publication/" cid)
                            :headers {"Content-Type" "application/json"
                                      "Accept" "application/json"}
                            :body (js/JSON.stringify (clj->js {:title title}))
                            :response-content-types {#"application/json" :json}
                            :on-success [::rename-ok]
                            :on-failure [::rename-fail]}}))
(rf/reg-event-fx ::rename-ok
                 (fn [{:keys [db]} [_ resp]]
                   {:db (assoc db :agora/viewed-publication (:body resp))
                    :dispatch [::search]}))
(rf/reg-event-db ::rename-fail (fn [db _] db))

;; --- components ------------------------------------------------------------

(def ^:private item-style
  {:display "block"
   :width "100%"
   :box-sizing "border-box"
   :text-align "left"
   :text-decoration "none"
   :padding "0.55em 0.7em"
   :border "1px solid #e0d6c2"
   :border-radius "0.35em"
   :background "#fff"
   :color "#333"
   :font-size "0.95em"
   :margin-bottom "0.4em"})

(def ^:private new-title-id "pub-new-title")

(defn- search-or-create
  "One box: typing filters your publications live; when the typed title isn't an exact existing one, a
  '＋ Create «title»' button opens a new publication. `results` is the current (filtered) list, used
  to decide whether the title already exists."
  [lang results]
  (r/with-let [q (r/atom "")]
              (let [qv (str/trim @q)
                    exact? (some #(= (str/lower-case (or (:title %) "")) (str/lower-case qv))
                                 results)]
                [:div {:style {:margin "0 0 1.2em"}}
                 [:input {:id new-title-id
                          :type "text"
                          :value @q
                          :placeholder (i18n/t lang :pub/search-ph)
                          :on-change #(let [v (.. % -target -value)]
                                        (reset! q v)
                                        (rf/dispatch [::search v]))
                          :style {:width "100%"
                                  :box-sizing "border-box"
                                  :padding "0.55em 0.7em"
                                  :font-size "0.95em"
                                  :border "1px solid #ccc"
                                  :border-radius "0.4em"}}]
                 (when (and (not (str/blank? qv)) (not exact?))
                   [:button {:on-click #(do (rf/dispatch [::create qv]) (reset! q ""))
                             :style {:width "100%"
                                     :box-sizing "border-box"
                                     :margin-top "0.3em"
                                     :border "1px dashed #2b8a3e"
                                     :background "#fff"
                                     :color "#2b8a3e"
                                     :border-radius "0.4em"
                                     :padding "0.5em"
                                     :cursor "pointer"
                                     :font-size "0.9em"}}
                    (str "＋ " (i18n/t lang :pub/create-q) " « " qv " »")])])))

(defn- create-fab
  "A mobile-only floating '+' that focuses the create-by-title input (hidden ≥640px by `.agora-fab`)."
  [lang]
  [:button {:class "agora-fab"
            :title (i18n/t lang :pub/new-ph)
            :on-click #(some-> (js/document.getElementById new-title-id)
                               (.focus))}
   "+"])

(defn publications-page
  "The publications index: a create-by-title control + a mobile FAB, then your open publications, each
  linking to its page. Logged-in only (the header entry that leads here is gated too)."
  []
  (let [lang @(rf/subscribe [::i18n/lang])
        pubs @(rf/subscribe [::results])]
    [:div {:style {:max-width "56em"
                   :margin "1.5em auto"
                   :padding "0 0.8em"
                   :font-family "system-ui, sans-serif"}}
     [:h1 {:style {:font-size "1.4em"
                   :margin "0 0 0.2em"}}
      (i18n/t lang :nav/publications)]
     [:p {:style {:color "#777"
                  :margin "0 0 1em"}}
      (i18n/t lang :pub/index-lead)]
     [search-or-create lang pubs]
     (if (seq pubs)
       (into [:div]
             (for [p pubs]
               ^{:key (:id p)}
               [:a {:href (i18n/publication lang (:id p))
                    :style item-style}
                (:title p)]))
       [:p {:style {:color "#aaa"}}
        (i18n/t lang :pub/none)])
     [create-fab lang]]))

(defn- status-pill
  [lang status]
  (when status
    (let [open? (= "open" status)]
      [:span {:style {:font-size "0.6em"
                      :font-weight 600
                      :letter-spacing "0.03em"
                      :text-transform "uppercase"
                      :padding "0.2em 0.6em"
                      :border-radius "1em"
                      :white-space "nowrap"
                      :color (if open? "#8a5a00" "#1d6b2f")
                      :background (if open? "#fff3d6" "#dff3e2")}}
       (i18n/t lang (if open? :pub/status-open :pub/status-closed))])))

(defn- title-bar
  "The publication page heading: 📖 title + status pill, with an owner-only ✎ that swaps the
  title for an inline rename input (Enter saves → new minor, Esc cancels). `editing` holds the
  draft title (an atom) or nil when not editing."
  [lang pub owner? editing]
  [:h1 {:style {:font-size "1.4em"
                :margin "0 0 0.2em"
                :color "#1b1a17"
                :display "flex"
                :align-items "center"
                :gap "0.5em"}}
   (if (some? @editing)
     [:span {:style {:display "flex"
                     :align-items "center"
                     :gap "0.3em"
                     :flex "1"}}
      [:input {:type "text"
               :value @editing
               :auto-focus true
               :on-change #(reset! editing (.. % -target -value))
               :on-key-down #(case (.-key %)
                               "Enter" (when-not (str/blank? @editing)
                                         (rf/dispatch [::rename (:id pub) (str/trim @editing)])
                                         (reset! editing nil))
                               "Escape" (reset! editing nil)
                               nil)
               :style {:flex "1"
                       :font-size "0.9em"
                       :padding "0.2em 0.4em"
                       :border "1px solid #ccc"
                       :border-radius "0.3em"}}]
      [:button {:on-click #(when-not (str/blank? @editing)
                             (rf/dispatch [::rename (:id pub) (str/trim @editing)])
                             (reset! editing nil))
                :style {:border "none"
                        :background "transparent"
                        :cursor "pointer"
                        :font-size "0.9em"
                        :color "#1d6b2f"}}
       "✓"]
      [:button {:on-click #(reset! editing nil)
                :style {:border "none"
                        :background "transparent"
                        :cursor "pointer"
                        :font-size "0.9em"
                        :color "#999"}}
       "✕"]]
     [:<>
      [:span (str "📖 " (or (:title pub) "…"))]
      (when owner?
        [:button {:on-click #(reset! editing (or (:title pub) ""))
                  :title (i18n/t lang :pub/rename)
                  :style {:border "none"
                          :background "transparent"
                          :cursor "pointer"
                          :font-size "0.7em"
                          :color "#8a5709"}}
         "✎"])
      (status-pill lang (:status pub))])])

(defn publication-page
  "A publication's page: its title (owner-renamable inline), status and its documents laid out as a
  discover-style card grid."
  []
  (r/with-let [editing (r/atom nil)]
              (let [lang @(rf/subscribe [::i18n/lang])
                    pub @(rf/subscribe [::viewed])
                    cards @(rf/subscribe [::viewed-cards])
                    user @(rf/subscribe [::auth/user])
                    owner? (and user (= (:id user) (:author-id pub)))]
                [:div {:style {:max-width "72em"
                               :margin "1.5em auto"
                               :padding "0 0.8em"
                               :font-family "system-ui, sans-serif"}}
                 [title-bar lang pub owner? editing]
                 [dv/byline (:author pub) (:published-at pub) (:author-id pub)]
                 [:p {:style {:color "#777"
                              :margin "0 0 1em"}}
                  (i18n/t lang :pub/page-lead)]
                 (cond
                   (nil? cards) nil
                   (empty? cards) [:p {:style {:color "#aaa"}}
                                   (i18n/t lang :pub/no-docs)]
                   :else (into [:div {:style {:display "grid"
                                              :grid-template-columns
                                              "repeat(auto-fill, minmax(min(17em, 100%), 1fr))"
                                              :gap "0.9em"}}]
                               (for [c cards] ^{:key (:id c)} [dv/discover-card lang c])))])))
