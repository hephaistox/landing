(ns landing.agora.frontend.publications
  "The publications working context: an in-flow left drawer to pick the publication you're
  authoring in (or return to the public view / create one), and a publication *page* that shows
  its documents like the discover grid. The chosen publication is the app-wide current context,
  persisted in localStorage."
  (:require
   [clojure.string                       :as str]
   [landing.agora.frontend.auth          :as auth]
   [landing.agora.frontend.document-page :as dv]
   [landing.agora.frontend.i18n          :as i18n]
   [landing.agora.frontend.ui-commons    :as ui]
   [re-frame.core                        :as rf]
   [reagent.core                         :as r]
   [superstructor.re-frame.fetch-fx]))

;; --- current-publication persistence (localStorage) -----------------------

(def ^:private storage-key "agora-current-publication")

(defn- read-stored
  []
  (try (not-empty (some-> js/localStorage
                          (.getItem storage-key)))
       (catch :default _ nil)))

(defn- write-stored!
  [id]
  (try (if id (.setItem js/localStorage storage-key id) (.removeItem js/localStorage storage-key))
       (catch :default _ nil)))

;; --- subs ------------------------------------------------------------------

(rf/reg-sub ::current (fn [db _] (:agora/current-publication db)))
(rf/reg-sub ::results (fn [db _] (:agora/publication-results db)))
(rf/reg-sub ::recent (fn [db _] (:agora/recent-documents db)))
(rf/reg-sub ::open? (fn [db _] (:agora/publications-panel-open? db)))
(rf/reg-sub ::viewed (fn [db _] (:agora/viewed-publication db)))
(rf/reg-sub ::viewed-cards (fn [db _] (:agora/viewed-publication-cards db)))
(rf/reg-sub ::current-title
            :<-
            [::current]
            :<-
            [::results]
            :<-
            [::viewed]
            (fn [[id results viewed] _]
              (or (some #(when (= (:id %) id) (:title %)) results)
                  (when (= (:id viewed) id) (:title viewed)))))

;; --- events ----------------------------------------------------------------

(defn- GET
  [url ok fail]
  {:method :get
   :url url
   :headers {"Accept" "application/json"}
   :response-content-types {#"application/json" :json}
   :on-success ok
   :on-failure fail})

(rf/reg-event-fx ::search
                 (fn [_ [_ q]]
                   {:fetch (GET (str "/agora/api/publication?q=" (js/encodeURIComponent (or q "")))
                                [::search-ok]
                                [::search-fail])}))
(rf/reg-event-db ::search-ok (fn [db [_ resp]] (assoc db :agora/publication-results (:body resp))))
(rf/reg-event-db ::search-fail (fn [db _] (assoc db :agora/publication-results [])))

;; the current publication's 20 most-recently-modified documents (latest on top) — the panel's
;; recent list; empty when no publication is selected (the list is publication-scoped)
(rf/reg-event-fx
 ::load-recent
 (fn [{:keys [db]} _]
   (if-let [cur (:agora/current-publication db)]
     {:fetch (GET (str "/agora/api/publication/" cur "/documents") [::recent-ok] [::recent-fail])}
     {:db (assoc db :agora/recent-documents [])})))
(rf/reg-event-db ::recent-ok
                 (fn [db [_ resp]] (assoc db :agora/recent-documents (vec (take 20 (:body resp))))))
(rf/reg-event-db ::recent-fail (fn [db _] (assoc db :agora/recent-documents [])))

;; set the working context (no navigation — the panel item is a link that navigates); the
;; recent list is publication-scoped, so reload it whenever the context changes
(rf/reg-event-fx ::select
                 (fn [{:keys [db]} [_ id]]
                   (write-stored! id)
                   {:db (assoc db :agora/current-publication id)
                    :dispatch [::load-recent]}))
(rf/reg-event-fx ::clear
                 (fn [{:keys [db]} _]
                   (write-stored! nil)
                   {:db (dissoc db :agora/current-publication)
                    :dispatch [::load-recent]}))

;; open/close the drawer — lazily loads my publications on open
(rf/reg-event-fx ::toggle
                 (fn [{:keys [db]} _]
                   (let [opening? (not (:agora/publications-panel-open? db))]
                     (cond-> {:db (update db :agora/publications-panel-open? not)}
                       opening? (assoc :dispatch-n [[::search ""] [::load-recent]])))))

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
(rf/reg-event-fx ::created
                 (fn [{:keys [db]} [_ resp]]
                   (let [id (:id (:body resp))]
                     (write-stored! id)
                     {:db (-> db
                              (dissoc :agora/publication-creating?)
                              (assoc :agora/current-publication id))
                      :dispatch-n [[::search ""] [::load-recent]]
                      :agora/navigate (i18n/publication (i18n/current db) id)})))
(rf/reg-event-db ::create-failed (fn [db _] (dissoc db :agora/publication-creating?)))

;; boot: adopt the stored context (the page/list load on demand)
(rf/reg-event-db ::adopt-stored
                 (fn [db _]
                   (let [id (read-stored)]
                     (cond-> db
                       id (assoc :agora/current-publication id)))))

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

;; rename a publication → new minor (owner only, server-checked); refresh the page + the panel
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
                    :dispatch [::search ""]}))
(rf/reg-event-db ::rename-fail (fn [db _] db))

;; --- components ------------------------------------------------------------

(def ^:private item-style
  {:display "block"
   :width "100%"
   :box-sizing "border-box"
   :text-align "left"
   :text-decoration "none"
   :padding "0.45em 0.6em"
   :border "1px solid #e0d6c2"
   :border-radius "0.35em"
   :background "#fff"
   :color "#333"
   :cursor "pointer"
   :font-size "0.88em"
   :margin-bottom "0.3em"})

(defn- item
  [active?]
  (cond-> item-style
    active? (assoc :background "#b9770e" :color "#fff" :border-color "#b9770e" :font-weight 600)))

(defn toggle-button
  "Header control showing the current publication (or the public view), toggling the drawer.
  Only for logged-in users — publications are yours to author in."
  []
  (when @(rf/subscribe [::auth/user])
    (let [lang @(rf/subscribe [::i18n/lang])
          cur @(rf/subscribe [::current])
          title @(rf/subscribe [::current-title])]
      [:button {:on-click #(rf/dispatch [::toggle])
                :title (i18n/t lang :pub/panel)
                :style {:border "1px solid #b9770e"
                        :background (if cur "#b9770e" "#fff")
                        :color (if cur "#fff" "#b9770e")
                        :border-radius "0.35em"
                        :padding "0.3em 0.7em"
                        :cursor "pointer"
                        :font-size "0.85em"
                        :max-width "12em"
                        :overflow "hidden"
                        :text-overflow "ellipsis"
                        :white-space "nowrap"}}
       (str "📖 " (if cur (or title "…") (i18n/t lang :pub/public)))])))

(defn- doc-link
  "A compact document link for the panel — draft mark + title."
  [lang d]
  [:a {:href (i18n/doc-url lang (:type d) (:id d))
       :style {:display "flex"
               :align-items "center"
               :gap "0.35em"
               :padding "0.3em 0.4em"
               :text-decoration "none"
               :color "#333"
               :font-size "0.83em"
               :border-bottom "1px solid #eee"}}
   (when (:draft d)
     [:span {:style {:color "#b98a3e"}}
      "✎"])
   [:span {:style {:overflow "hidden"
                   :text-overflow "ellipsis"
                   :white-space "nowrap"}}
    (or (:title d) (:name d))]])

(def ^:private section-style
  {:margin-top "1.1em"
   :border-top "1px solid #e0d6c2"
   :padding-top "0.6em"})

(def ^:private section-label-style
  {:font-weight 700
   :color "#8a7a55"
   :font-size "0.75em"
   :text-transform "uppercase"
   :letter-spacing "0.04em"
   :margin-bottom "0.35em"})

(def ^:private empty-hint-style
  {:color "#aaa"
   :font-size "0.8em"})

(defn panel
  "The in-flow left drawer, top→bottom: a search-or-create box, the current publication (with a
  ✕ back to the public view), your recent publications, then the current publication's recently-
  modified documents. Nothing when closed or logged out."
  []
  (r/with-let
   [q (r/atom "")]
   (when (and @(rf/subscribe [::open?]) @(rf/subscribe [::auth/user]))
     (let [lang @(rf/subscribe [::i18n/lang])
           cur @(rf/subscribe [::current])
           cur-title @(rf/subscribe [::current-title])
           results @(rf/subscribe [::results])
           recent @(rf/subscribe [::recent])
           qv (str/trim @q)
           exact? (some #(= (str/lower-case (or (:title %) "")) (str/lower-case qv)) results)
           others (remove #(= (:id %) cur) results)
           ;; a blank query lists your 5 newest; while searching the box is the full list
           recent-pubs (if (str/blank? qv) (take 5 others) others)]
       [:div {:style {:flex "0 0 16em"
                      :max-width "80vw"
                      :align-self "stretch"
                      :position "sticky"
                      :top 0
                      :max-height "100vh"
                      :overflow-y "auto"
                      :background "#faf6ee"
                      :border-right "1px solid #e0d6c2"
                      :padding "0.8em"
                      :box-sizing "border-box"}}
        [:div {:style {:display "flex"
                       :align-items "center"
                       :justify-content "space-between"
                       :margin-bottom "0.7em"}}
         [:strong {:style {:color "#8a5709"}}
          (i18n/t lang :pub/panel)]
         [:button {:on-click #(rf/dispatch [::toggle])
                   :style {:border "none"
                           :background "transparent"
                           :cursor "pointer"
                           :font-size "1.1em"}}
          "✕"]]
        ;; 1 — search or create
        [ui/composed-field {:type "text"
                            :placeholder (i18n/t lang :pub/search-ph)
                            :value @q
                            :on-text #(do (reset! q %) (rf/dispatch [::search %]))
                            :style {:width "100%"
                                    :box-sizing "border-box"
                                    :padding "0.4em"
                                    :font-size "0.85em"
                                    :border "1px solid #ccc"
                                    :border-radius "0.35em"}}]
        (when (and (not (str/blank? qv)) (not exact?))
          [:button {:on-click #(do (rf/dispatch [::create qv]) (reset! q ""))
                    :style {:width "100%"
                            :box-sizing "border-box"
                            :margin-top "0.2em"
                            :border "1px dashed #2b8a3e"
                            :background "#fff"
                            :color "#2b8a3e"
                            :border-radius "0.35em"
                            :padding "0.4em"
                            :cursor "pointer"
                            :font-size "0.85em"}}
           (str "＋ " (i18n/t lang :pub/create-q) " « " qv " »")])
        ;; 2 — the current context: the selected publication (✕ back to public) or the public view
        (if cur
          [:div {:style {:display "flex"
                         :align-items "center"
                         :gap "0.3em"
                         :margin-top "0.6em"}}
           [:a {:href (i18n/publication lang cur)
                :style (merge (item true)
                              {:flex "1"
                               :margin-bottom 0
                               :overflow "hidden"
                               :text-overflow "ellipsis"
                               :white-space "nowrap"})}
            (str "📖 " (or cur-title "…"))]
           [:button {:on-click #(rf/dispatch [::clear])
                     :title (i18n/t lang :pub/public-view)
                     :style {:border "none"
                             :background "transparent"
                             :cursor "pointer"
                             :font-size "1em"
                             :color "#8a5709"
                             :padding "0.2em 0.3em"}}
            "✕"]]
          [:a {:href (i18n/discover lang)
               :style (merge (item true) {:margin-top "0.6em"})}
           (str "🌐 " (i18n/t lang :pub/public-view))])
        ;; 3 — recent publications (excluding the selected one)
        [:div {:style section-style}
         [:div {:style section-label-style}
          (i18n/t lang :pub/recent-pubs)]
         (if (seq recent-pubs)
           (into [:div]
                 (for [p recent-pubs]
                   ^{:key (:id p)}
                   [:a {:href (i18n/publication lang (:id p))
                        :on-click #(rf/dispatch [::select (:id p)])
                        :style (item false)}
                    (:title p)]))
           [:div {:style empty-hint-style}
            (i18n/t lang :pub/none)])]
        ;; 4 — the current publication's recently-modified documents
        (when cur
          [:div {:style section-style}
           [:div {:style section-label-style}
            (i18n/t lang :pub/recent)]
           (if (seq recent)
             (into [:div] (for [d recent] ^{:key (:id d)} [doc-link lang d]))
             [:div {:style empty-hint-style}
              (i18n/t lang :pub/no-docs)])])]))))

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
  "The publication's page: its title (owner-renamable inline), status and its documents laid out
  as a discover-style card grid."
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
