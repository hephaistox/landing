(ns landing.agora.frontend.publications
  "Publications — the work-packages you author in."
  (:require
   [clojure.string                       :as str]
   [landing.agora.document.kind          :as dk]
   [landing.agora.frontend.auth          :as auth]
   [landing.agora.frontend.document-page :as dv]
   [landing.agora.frontend.i18n          :as i18n]
   [landing.agora.frontend.modal         :as modal]
   [landing.agora.frontend.ui-commons    :as ui]
   [landing.language                     :as language]
   [re-frame.core                        :as rf]
   [reagent.core                         :as r]
   [superstructor.re-frame.fetch-fx]))

;; --- subs ------------------------------------------------------------------

(rf/reg-sub ::results (fn [db _] (:agora/publication-results db)))
(rf/reg-sub ::viewed (fn [db _] (:agora/viewed-publication db)))
(rf/reg-sub ::viewed-cards (fn [db _] (:agora/viewed-publication-cards db)))
(rf/reg-sub ::publishing? (fn [db _] (:agora/publishing? db)))

;; --- active publication (the work-package you are authoring in) ------------
;; Persisted in localStorage as `{:id :title :owner-id}` — every document create/edit attaches to it.
;; `:owner-id` lets a session load drop a publication left by another account (`::reconcile-active`).
;; May be synced to the account asynchronously later; for now it is client-side only.

(def ^:private active-key "agora-active-publication")

(defn- read-active
  []
  (try (some-> js/localStorage
               (.getItem active-key)
               js/JSON.parse
               (js->clj :keywordize-keys true))
       (catch :default _ nil)))

(defn- write-active!
  [m]
  (try (if m
         (.setItem js/localStorage active-key (js/JSON.stringify (clj->js m)))
         (.removeItem js/localStorage active-key))
       (catch :default _ nil)))

(defn- ->active
  "The active-publication record persisted client-side: `:id` + `:title` + its `:owner-id`, so a
  publication belonging to another account (a value left in localStorage after an account switch) can
  be recognized and dropped. A publication's owner is its byline `:attributed-author-id`."
  [pub]
  {:id (:id pub)
   :title (:title pub)
   :owner-id (:attributed-author-id pub)})

(rf/reg-sub ::active (fn [db _] (:agora/active-publication db)))

(defn active-param
  "A `&publication-id=<cid>` query fragment for the active publication, so browse/search fetches overlay
  its drafts. Empty when none is active. `db` is the re-frame db."
  [db]
  (if-let [id (:id (:agora/active-publication db))]
    (str "&publication-id=" (js/encodeURIComponent id))
    ""))

(defn active-query
  "A `?publication-id=<cid>` query string for the active publication — for a URL with no other query (the
  by-id/by-major page reads), so its draft successors overlay. Empty when none is active."
  [db]
  (if-let [id (:id (:agora/active-publication db))]
    (str "?publication-id=" (js/encodeURIComponent id))
    ""))

;; adopt the stored active publication at boot
(rf/reg-event-db ::adopt-active
                 (fn [db _]
                   (if-let [m (read-active)]
                     (assoc db :agora/active-publication m)
                     db)))

;; `core/refetch-scoped` is dispatched by a fully-qualified keyword (not a `::core/…` alias) so this
;; namespace need not require `core` — `core` requires this one, and a mutual require would cycle. It
;; re-runs the current discover/search view so the active-publication overlay applies at once.
(def ^:private refetch-scoped :landing.agora.frontend.core/refetch-scoped)

(rf/reg-event-fx ::set-active
                 (fn [{:keys [db]} [_ pub]]
                   (let [m (->active pub)]
                     (write-active! m)
                     {:db (assoc db :agora/active-publication m)
                      :dispatch [refetch-scoped]})))

;; drop the active publication when it isn't the current user's — a value left in localStorage after
;; a different account used this browser (an open publication is private to its owner and must not
;; surface for anyone else). Dispatched by auth whenever a session loads (login / session check).
(rf/reg-event-fx
 ::reconcile-active
 (fn [{:keys [db]} _]
   (let [active (:agora/active-publication db)
         uid (:id (get db ::auth/user))]
     (if (and active (not= (:owner-id active) uid)) {:dispatch [::clear-active]} {}))))

;; create a document from this publication's page: make it the active publication, then open the
;; authoring form at `url`, so the new document lands in this publication
(rf/reg-event-fx ::create-here
                 (fn [{:keys [db]} [_ pub url]]
                   (let [m (->active pub)]
                     (write-active! m)
                     {:db (assoc db :agora/active-publication m)
                      :agora/navigate url})))

(rf/reg-event-fx ::clear-active
                 (fn [{:keys [db]} _]
                   (write-active! nil)
                   {:db (dissoc db :agora/active-publication)
                    :dispatch [refetch-scoped]}))

;; deselecting the active publication from the header = "look at all publications": clear it and land
;; on the index
(rf/reg-event-fx ::leave-to-index
                 (fn [{:keys [db]} _]
                   (write-active! nil)
                   {:db (dissoc db :agora/active-publication)
                    :agora/navigate (i18n/publications (i18n/current db))}))

;; Ensure an active publication before an authoring action: if one is active, run `then-event`
;; immediately; otherwise auto-create one (blank title → the server auto-names it `publication<N>`),
;; set it active, and then run `then-event`. So create/edit never blocks on picking a publication.
(rf/reg-event-fx ::ensure-active
                 (fn [{:keys [db]} [_ then-event]]
                   (cond
                     ;; already in flight — ignore repeat clicks (double-click guard)
                     (:agora/authoring-busy? db) {}
                     ;; a publication is active — run the authoring action now
                     (:agora/active-publication db) {:db (assoc db :agora/authoring-busy? true)
                                                     :dispatch then-event}
                     ;; none active — auto-create one (server auto-names), set active, then run
                     :else {:db (assoc db :agora/authoring-busy? true)
                            :fetch {:method :post
                                    :url "/agora/api/publication"
                                    :headers {"Content-Type" "application/json"
                                              "Accept" "application/json"}
                                    :body (js/JSON.stringify (clj->js {}))
                                    :response-content-types {#"application/json" :json}
                                    :on-success [::ensured then-event]
                                    :on-failure [::ensure-failed]}})))
(rf/reg-event-fx ::ensured
                 (fn [{:keys [db]} [_ then-event resp]]
                   (let [m (->active (:body resp))]
                     (write-active! m)
                     ;; stay busy — the authoring action (`then-event`) clears it on success/failure.
                     ;; The index is not refreshed here; it reloads when the user next visits it.
                     {:db (assoc db :agora/active-publication m)
                      :dispatch then-event})))
(rf/reg-event-db ::ensure-failed
                 (fn [db _]
                   (js/console.error "[agora] auto-create publication failed")
                   (dissoc db :agora/authoring-busy?)))

;; --- events ----------------------------------------------------------------

(defn- GET
  [url ok fail]
  {:method :get
   :url url
   :headers {"Accept" "application/json"}
   :response-content-types {#"application/json" :json}
   :on-success ok
   :on-failure fail})

;; the index list — every visible publication; the shared browse filter narrows it client-side
(rf/reg-event-fx ::search
                 (fn [_ _]
                   {:fetch (GET "/agora/api/publication?scope=all" [::search-ok] [::search-fail])}))
(rf/reg-event-db ::search-ok (fn [db [_ resp]] (assoc db :agora/publication-results (:body resp))))
(rf/reg-event-db ::search-fail (fn [db _] (assoc db :agora/publication-results [])))

;; the index status filter — publications have no content language, so instead of the language filter
;; they get a lifecycle toggle: all / published (closed) / open. Defaults to the open ones — the
;; work in progress is what you come here to act on.
(rf/reg-sub ::status-filter (fn [db _] (:agora/pub-status-filter db :open)))
(rf/reg-event-db ::set-status-filter (fn [db [_ v]] (assoc db :agora/pub-status-filter v)))

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

;; publish (close) a publication → its drafts go public; the publication closes (owner-only, checked
;; server-side). Refresh the page (now closed) and its cards (now published), drop it from the open
;; index, and clear it as the active publication if it was.
(rf/reg-sub ::publish-error (fn [db _] (:agora/publish-error db)))

(rf/reg-event-fx ::publish
                 (fn [{:keys [db]} [_ cid]]
                   {:db (-> db
                            (assoc :agora/publishing? true)
                            (dissoc :agora/publish-error))
                    :fetch {:method :post
                            :url (str "/agora/api/publication/" cid "/publish")
                            :headers {"Content-Type" "application/json"
                                      "Accept" "application/json"}
                            :response-content-types {#"application/json" :json}
                            :on-success [::publish-ok cid]
                            :on-failure [::publish-fail]}}))
(rf/reg-event-fx ::publish-ok
                 (fn [{:keys [db]} [_ cid resp]]
                   (when (= cid (get-in db [:agora/active-publication :id])) (write-active! nil))
                   {:db (cond-> (-> db
                                    (dissoc :agora/publishing?)
                                    (assoc :agora/viewed-publication (:body resp)))
                          (= cid (get-in db [:agora/active-publication :id]))
                          (dissoc :agora/active-publication))
                    :dispatch-n [[::search] [::load-page-cards cid]]}))
;; a 422 means some gathered drafts still have errors — flag it so the button area says so (the cards
;; below carry the bells, each document's own page the specific fixes)
(rf/reg-event-db ::publish-fail
                 (fn [db [_ resp]]
                   (let [docs (get-in resp [:body :documents])]
                     (-> db
                         (dissoc :agora/publishing?)
                         (assoc :agora/publish-error {:count (when (coll? docs) (count docs))})))))

;; delete a draft document of publication `cid`; refresh the publication's cards
(rf/reg-event-fx ::delete-doc
                 (fn [_ [_ cid type doc-id]]
                   {:fetch {:method :delete
                            :url (str "/agora/api/documents/" type
                                      "/" doc-id
                                      "?publication-id=" (js/encodeURIComponent cid))
                            :headers {"Accept" "application/json"}
                            :response-content-types {#"application/json" :json}
                            :on-success [::load-page-cards cid]
                            :on-failure [::delete-doc-fail]}}))
(rf/reg-event-db ::delete-doc-fail (fn [db _] db))

;; delete an open publication and its drafts (confirmed by the shared `modal/confirm`); leave for the
;; index, drop it as the active publication
(rf/reg-event-fx ::delete-pub
                 (fn [{:keys [db]} [_ cid]]
                   (when (= cid (get-in db [:agora/active-publication :id])) (write-active! nil))
                   {:db (cond-> db
                          (= cid (get-in db [:agora/active-publication :id]))
                          (dissoc :agora/active-publication))
                    :fetch {:method :delete
                            :url (str "/agora/api/publication/" cid)
                            :headers {"Accept" "application/json"}
                            :response-content-types {#"application/json" :json}
                            :on-success [::deleted-pub]
                            :on-failure [::delete-pub-fail]}}))
(rf/reg-event-fx ::deleted-pub
                 (fn [{:keys [db]} _] {:agora/navigate (i18n/publications (i18n/current db))}))
(rf/reg-event-db ::delete-pub-fail (fn [db _] db))

;; --- components ------------------------------------------------------------

(defn- create-from-filter
  "When the shared browse filter's `q` names no existing publication, offer to create one titled `q` —
  the create-by-typing affordance, reading the shared filter rather than a box of its own."
  [lang pubs]
  (let [q (str/trim (or (:q @(rf/subscribe [::dv/browse-filter])) ""))
        exact? (some #(= (str/lower-case (or (:title %) "")) (str/lower-case q)) pubs)]
    (when (and (seq q) (not exact?))
      [:button {:on-click #(rf/dispatch [::create q])
                :style {:margin "0 0 1em"
                        :border "1px dashed #2b8a3e"
                        :background "#fff"
                        :color "#2b8a3e"
                        :border-radius "0.4em"
                        :padding "0.5em 0.9em"
                        :cursor "pointer"
                        :font-size "0.9em"}}
       (str "＋ " (i18n/t lang :pub/create-q) " « " q " »")])))

(defn- create-fab
  "A mobile-only floating '+' opening a new, auto-named publication (hidden ≥640px by `.agora-fab`)."
  [lang]
  [:button {:class "agora-fab"
            :title (i18n/t lang :pub/new-ph)
            :on-click #(rf/dispatch [::create ""])}
   "+"])

(defn- status-toggle
  "The publications index lifecycle filter, as an Excel-style combobox — open / published (closed) /
  all (a publication has no content language, so it gets this instead of the language filter)."
  [lang status]
  (let [opts [[:open (i18n/t lang :pub/status-open)]
              [:closed (i18n/t lang :pub/status-closed)]
              [:all (i18n/t lang :filter/all)]]
        current (some (fn [[v l]] (when (= v status) l)) opts)]
    [dv/filter-dropdown {:label (i18n/t lang :pub/filter-status)
                         :active? (not= status :all)
                         :summary current}
     (into [:div {:style {:display "flex"
                          :flex-direction "column"}}]
           (for [[v l] opts]
             ^{:key v} [dv/check-row (= status v) l #(rf/dispatch [::set-status-filter v])]))]))

(defn publications-page
  "The publications index: a lifecycle status toggle + the shared browse filter (author / q), a
  create-by-typing control, then the publications it keeps as the shared discover grid of cards (each a
  publication card, driven by its `:type`/`:status`), plus a mobile FAB. Logged-in only (the header
  entry that leads here is gated too)."
  []
  (let [lang @(rf/subscribe [::i18n/lang])
        ;; creating a publication is a contributor action — hidden from an anonymous visitor who
        ;; reaches this index directly (the header entry that normally leads here is gated too)
        logged-in? (some? @(rf/subscribe [::auth/user]))
        status @(rf/subscribe [::status-filter])
        pubs @(rf/subscribe [::results])
        by-status (case status
                    :open (filterv #(= "open" (:status %)) pubs)
                    :closed (filterv #(= "closed" (:status %)) pubs)
                    pubs)
        shown (dv/filter-items by-status)]
    [:div {:style {:max-width "72em"
                   :margin "1.5em auto"
                   :padding "0 0.8em"
                   :font-family "system-ui, sans-serif"}}
     [:h1 {:style {:font-size "1.4em"
                   :margin "0 0 0.2em"}}
      (i18n/t lang :nav/publications)]
     [:p {:style {:color "#777"
                  :margin "0 0 1em"}}
      (i18n/t lang :pub/index-lead)]
     [status-toggle lang status]
     [dv/filter-bar lang nil nil]
     (when logged-in? [create-from-filter lang pubs])
     (if (seq shown)
       [dv/card-grid lang shown nil]
       [:p {:style {:color "#aaa"}}
        (i18n/t lang :pub/none)])
     (when logged-in? [create-fab lang])]))

(defn active-chip
  "The header's publications entry (logged-in only), replacing a plain Publications link: when a
  publication is **active** (the one create/edit attach to) it shows as a chip with a ✕ that
  deselects it — deselecting means 'look at all publications', so it lands on the index. With none
  active it is just a link to that index."
  []
  (when @(rf/subscribe [::auth/user])
    (let [lang @(rf/subscribe [::i18n/lang])]
      (if-let [pub @(rf/subscribe [::active])]
        [:span {:style {:display "inline-flex"
                        :align-items "center"
                        :gap "0.3em"
                        :max-width "14em"
                        :background "#b9770e"
                        :color "#fff"
                        :border-radius "0.35em"
                        :padding "0.2em 0.5em"
                        :font-size "0.8em"}}
         [:a {:href (i18n/publication lang (:id pub))
              :title (:title pub)
              :style {:color "#fff"
                      :text-decoration "none"
                      :overflow "hidden"
                      :text-overflow "ellipsis"
                      :white-space "nowrap"}}
          (str "📖 " (:title pub))]
         [:button {:on-click #(rf/dispatch [::leave-to-index])
                   :title (i18n/t lang :pub/leave)
                   :style {:border "none"
                           :background "transparent"
                           :color "#fff"
                           :cursor "pointer"
                           :line-height 1
                           :padding 0}}
          "✕"]]
        [:a {:href (i18n/publications lang)
             :style {:color "#e8e2d6"
                     :text-decoration "none"
                     :opacity 0.85
                     :font-size "0.9em"}}
         (i18n/t lang :nav/publications)]))))

(defn- export-icon
  "A Lucide upload glyph — a tray with an up arrow, for the publish action."
  []
  [:svg {:width "1.05em"
         :height "1.05em"
         :viewBox "0 0 24 24"
         :fill "none"
         :stroke "currentColor"
         :stroke-width 2
         :stroke-linecap "round"
         :stroke-linejoin "round"
         :style {:vertical-align "-0.15em"}}
   [:path {:d "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"}]
   [:polyline {:points "17 8 12 3 7 8"}]
   [:line {:x1 12
           :y1 3
           :x2 12
           :y2 15}]])

(defn- publish-button
  "Publish (close) the publication — its drafts go public, confirmed. A hover tooltip explains it.
  Sits in the title row beside the rename and delete icons: a bare glyph on a narrow screen, a
  labelled green pill from 640px (`.agora-publish` in the shell)."
  [lang pub]
  (let [publishing? @(rf/subscribe [::publishing?])]
    [dv/tooltip
     (i18n/t lang :pub/publish-hint)
     [:button {:class "agora-publish"
               :on-click #(when-not publishing?
                            (rf/dispatch [::modal/confirm {:message (i18n/t lang
                                                                            :pub/publish-confirm)
                                                           :confirm-label (i18n/t lang :pub/publish)
                                                           :on-confirm [::publish (:id pub)]}]))
               :disabled (boolean publishing?)
               :style {:cursor (if publishing? "default" "pointer")
                       :opacity (if publishing? 0.5 1)}}
      [export-icon]
      [:span {:class "agora-publish__label"}
       (i18n/t lang (if publishing? :pub/publishing :pub/publish))]]]))

(defn- title-bar
  "The publication page heading: 📖 title, then the rename, delete and publish controls. The owner
  renames by **double-clicking** the title (or the small pencil beside it) — an inline input, Enter
  or blur saves (→ new minor), Esc cancels. `editing` holds the draft title (an atom) or nil."
  [lang pub owner? editing]
  (let [open-owner? (and owner? (= "open" (:status pub)))
        save! (fn []
                (when-not (str/blank? @editing)
                  (rf/dispatch [::rename (:id pub) (str/trim @editing)]))
                (reset! editing nil))]
    [:h1 {:style {:font-size "1.4em"
                  :margin "0 0 0.2em"
                  :color "#1b1a17"
                  :display "flex"
                  :align-items "center"
                  :flex-wrap "wrap"
                  :gap "0.35em"}}
     (if (some? @editing)
       [ui/composed-field {:type "text"
                           :value @editing
                           :auto-focus true
                           :on-text #(reset! editing %)
                           :on-blur save!
                           :on-key-down #(case (.-key %)
                                           "Enter" (save!)
                                           "Escape" (reset! editing nil)
                                           nil)
                           :style {:flex "1"
                                   :font-size "0.9em"
                                   :padding "0.2em 0.4em"
                                   :border "1px solid #ccc"
                                   :border-radius "0.3em"}}]
       [:<>
        [:span {:on-double-click (when owner? #(reset! editing (or (:title pub) "")))
                :title (when owner? (i18n/t lang :pub/rename-hint))
                :style {:cursor (if owner? "text" "default")}}
         (str "📖 " (or (:title pub) "…"))]
        (when owner?
          [dv/tooltip
           (i18n/t lang :pub/rename)
           [:button {:on-click #(reset! editing (or (:title pub) ""))
                     :style {:border "none"
                             :background "transparent"
                             :cursor "pointer"
                             :font-size "0.55em"
                             :color "#b98a3e"
                             :padding 0
                             :line-height 1}}
            "✎"]])
        ;; delete sits right beside edit — a ✕ opening a warning modal (loses the publication and all
        ;; its drafts); owner of an open publication only
        (when open-owner?
          [dv/tooltip
           (i18n/t lang :pub/delete)
           [:button {:on-click #(rf/dispatch [::modal/confirm
                                              {:message (i18n/t lang :pub/delete-confirm)
                                               :danger? true
                                               :on-confirm [::delete-pub (:id pub)]}])
                     :style {:border "none"
                             :background "transparent"
                             :cursor "pointer"
                             :font-size "0.55em"
                             :color "#c92a2a"
                             :padding 0
                             :line-height 1}}
            "✕"]])
        ;; publish closes the row of controls — the primary action, so it keeps its label and green
        ;; pill once the screen is wide enough
        (when open-owner? [publish-button lang pub])])]))

(defn- status-pill
  "The publication's status as a rounded chip (a hover tooltip explains it). Only for a **closed**
  publication — an open one already shows the Publish button, which conveys the same thing."
  [lang pub]
  (when-not (= "open" (:status pub))
    [dv/tooltip
     (i18n/t lang :pub/status-closed-hint)
     [:span {:style {:display "inline-flex"
                     :align-items "center"
                     :padding "0.35em 0.9em"
                     :font-size "0.85em"
                     :font-weight 600
                     :letter-spacing "0.03em"
                     :text-transform "uppercase"
                     :border-radius "1em"
                     :cursor "help"
                     :color "#1d6b2f"
                     :background "#dff3e2"}}
      (i18n/t lang :pub/status-closed)]]))

(defn- publish-error
  "A red line under the title when a publish was refused because a gathered document is in error — it
  points at the bells on the cards below."
  [lang]
  (when-let [err @(rf/subscribe [::publish-error])]
    [:div {:style {:color "#c92a2a"
                   :font-size "0.8em"
                   :max-width "24em"
                   :margin "0 0 0.4em"}}
     (str "🔔 " (i18n/t lang :pub/publish-blocked) (when-let [n (:count err)] (str " (" n ")")))]))

(defn- active-toggle
  "Whether you are **working in** this publication — the one every document create/edit (from anywhere)
  attaches to. A labelled switch: a sliding knob (on = copper) beside « Travailler ici », so it reads
  as an on/off toggle rather than a bare checkbox. The header chip shows and holds the active one."
  [lang pub]
  (let [active? (= (:id @(rf/subscribe [::active])) (:id pub))]
    [dv/tooltip
     (i18n/t lang :pub/create-here)
     [:button {:on-click #(rf/dispatch (if active? [::clear-active] [::set-active pub]))
               :style {:display "inline-flex"
                       :align-items "center"
                       :gap "0.5em"
                       :border "none"
                       :background "transparent"
                       :cursor "pointer"
                       :font-size "0.82em"
                       :font-weight 600
                       :color (if active? "#b9770e" "#8a7a55")
                       :padding "0.1em"}}
      ;; the switch: a track with a knob that slides right + turns copper when active
      [:span {:style {:position "relative"
                      :display "inline-block"
                      :width "2em"
                      :height "1.1em"
                      :border-radius "1em"
                      :background (if active? "#b9770e" "#ccc")
                      :transition "background 0.15s"}}
       [:span {:style {:position "absolute"
                       :top "0.15em"
                       :left (if active? "1.05em" "0.15em")
                       :width "0.8em"
                       :height "0.8em"
                       :border-radius "50%"
                       :background "#fff"
                       :transition "left 0.15s"}}]]
      (i18n/t lang :pub/work-here)]]))

(defn- create-doc-fab
  "A floating create control (`position: fixed`, bottom-right) so it stays visible however long the grid
  grows or however small the viewport — a '＋' that toggles open the two document types. A scroll/resize
  listener measures how far the footer rises into the viewport and lifts the button by that much, so it
  never overlaps the footer at the bottom of the page."
  [_lang _pub]
  (let [open? (r/atom false)
        ;; px the footer pushes into the viewport from the bottom — the button rises by this much
        lift (r/atom 0)
        recalc (fn []
                 (let [f (.querySelector js/document "footer")
                       vh (.-innerHeight js/window)]
                   (reset! lift (if f (max 0 (- vh (.-top (.getBoundingClientRect f)))) 0))))]
    (r/create-class
     {:display-name "create-doc-fab"
      :component-did-mount (fn [_]
                             (recalc)
                             (.addEventListener js/window "scroll" recalc #js {:passive true})
                             (.addEventListener js/window "resize" recalc))
      :component-will-unmount (fn [_]
                                (.removeEventListener js/window "scroll" recalc)
                                (.removeEventListener js/window "resize" recalc))
      :reagent-render
      (fn [lang pub]
        (let [opt (fn [label url] [:button {:on-click #(rf/dispatch [::create-here pub url])
                                            :style {:border "none"
                                                    :background "#b9770e"
                                                    :color "#fff"
                                                    :border-radius "1.2em"
                                                    :padding "0.5em 0.9em"
                                                    :font-size "0.85em"
                                                    :font-weight 600
                                                    :cursor "pointer"
                                                    :box-shadow "0 2px 8px rgba(0,0,0,0.25)"
                                                    :white-space "nowrap"}}
                                   (str "＋ " label)])]
          [:div {:style {:position "fixed"
                         :bottom (str "calc(1.4em + " @lift "px)")
                         :right "1.4em"
                         :z-index 50
                         :display "flex"
                         :flex-direction "column"
                         :align-items "flex-end"
                         :gap "0.5em"}}
           (when @open?
             [:<>
              (opt (i18n/t lang :nav/new-ki) (i18n/new-ki lang))
              (opt (i18n/t lang :nav/new-article) (i18n/new-article lang))])
           [:button {:on-click #(swap! open? not)
                     :title (i18n/t lang :pub/new-ki-hint)
                     :style {:width "3em"
                             :height "3em"
                             :border-radius "50%"
                             :border "none"
                             :background "#b9770e"
                             :color "#fff"
                             :font-size "1.6em"
                             :cursor "pointer"
                             :box-shadow "0 3px 10px rgba(0,0,0,0.3)"
                             :line-height 1}}
            (if @open? "×" "＋")]]))})))

(defn- page-actions
  "The publication page's meta row: the status chip and, for the owner of an open publication, the
  'work here' toggle. (Publish sits top-right by the title; rename / delete beside it; creation is the
  floating create button.)"
  [lang pub owner?]
  (when pub
    (let [editable? (and owner? (= "open" (:status pub)))]
      [:div {:style {:display "flex"
                     :flex-wrap "wrap"
                     :align-items "center"
                     :gap "0.9em"
                     :margin "0.3em 0 1.2em"}}
       [status-pill lang pub]
       (when editable? [active-toggle lang pub])
       [:a {:href (i18n/publication-graph lang (:id pub))
            :style {:color "#b9770e"
                    :text-decoration "none"
                    :font-size "0.85em"
                    :font-weight 600}}
        (str "🕸 " (i18n/t lang :graph/link))]])))

(defn- doc-card
  "A document card on the publication page, with a delete ✕ when `deletable?` (owner of an open
  publication) — removing the draft from the publication."
  [lang cid deletable? c]
  ;; `discover-card` already shows the error bell (from the card's `:errors`); the specific list is on
  ;; the document's own page, one click away
  [:div {:style {:position "relative"}}
   [dv/discover-card lang c]
   (when deletable?
     [:button {:on-click #(rf/dispatch [::modal/confirm
                                        {:message (i18n/t lang :pub/delete-doc-confirm)
                                         :danger? true
                                         :on-confirm [::delete-doc cid (:type c) (:id c)]}])
               :title (i18n/t lang :pub/delete-doc)
               :style {:position "absolute"
                       :top "0.4em"
                       :right "0.4em"
                       :border "none"
                       :background "rgba(255,255,255,0.92)"
                       :color "#c92a2a"
                       :border-radius "50%"
                       :width "1.7em"
                       :height "1.7em"
                       :cursor "pointer"
                       :font-size "0.9em"
                       :line-height 1
                       :box-shadow "0 1px 3px rgba(0,0,0,0.2)"}}
      "✕"])])

(defn- header-skeleton
  "Placeholder shown while the publication is loading — before its real title/status/byline arrive,
  so no stale or wrong information (e.g. a false 'published') flashes."
  []
  [:div {:style {:margin "0.3em 0"}}
   [:div {:class "agora-skel"
          :style {:width "16em"
                  :max-width "80%"
                  :height "1.7em"
                  :border-radius "0.3em"
                  :margin-bottom "0.6em"}}]
   [:div {:class "agora-skel"
          :style {:width "9em"
                  :height "0.9em"
                  :border-radius "0.3em"
                  :margin-bottom "0.9em"}}]
   [:div {:class "agora-skel"
          :style {:width "12em"
                  :height "1.9em"
                  :border-radius "1em"}}]])

(defn publication-page
  "A publication's page: its title (owner-renamable by double-click), the status+publish control, and
  its documents laid out as a discover-style card grid. A loading skeleton shows until the
  publication's data arrives."
  []
  (r/with-let
   [editing (r/atom nil)]
   (let [lang @(rf/subscribe [::i18n/lang])
         pub @(rf/subscribe [::viewed])
         cards @(rf/subscribe [::viewed-cards])
         user @(rf/subscribe [::auth/user])
         owner? (and user (= (:id user) (:attributed-author-id pub)))
         deletable? (and owner? (= "open" (:status pub)))
         loaded? (some? (:status pub))]
     [:div {:style {:max-width "72em"
                    :margin "1.5em auto"
                    :padding "0 0.8em"
                    :font-family "system-ui, sans-serif"}}
      (if-not loaded?
        [header-skeleton]
        [:<>
         [title-bar lang pub owner? editing]
         [publish-error lang]
         [dv/byline
          (:attributed-author pub)
          (:published-at pub)
          (:attributed-author-id pub)
          (i18n/t lang (if (= "open" (:status pub)) :pub/date-open-hint :pub/date-closed-hint))]
         [page-actions lang pub owner?]
         ;; filters only earn their room once there are several documents — a small draft cluster
         ;; needs no filtering
         (when (>= (count cards) 5)
           [dv/filter-bar
            lang
            (filterv (into #{}
                           (keep #(some-> (:kind %)
                                          keyword))
                           cards)
                     dk/kind-ids)
            (filterv (into #{}
                           (keep #(some-> (:lang %)
                                          name))
                           cards)
                     language/languages)])
         (cond
           (nil? cards) nil
           ;; the floating '＋' is the one create affordance — no in-grid tiles needed
           (empty? cards) [:p {:style {:color "#aaa"}}
                           (i18n/t lang :pub/no-docs)]
           :else (into [:div {:style {:display "grid"
                                      :grid-template-columns
                                      "repeat(auto-fill, minmax(min(17em, 100%), 1fr))"
                                      :gap "0.9em"}}]
                       (for [c (dv/filter-items cards)]
                         ^{:key (:id c)} [doc-card lang (:id pub) deletable? c])))
         (when deletable? [create-doc-fab lang pub])])])))
