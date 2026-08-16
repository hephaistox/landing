(ns landing.agora.frontend.edit
  "Generic authoring components + events for **any** document type — no type knowledge of
  its own. The in-place edit card, the standalone create form, and their re-frame events
  are all driven by a `cfg` map supplied by the per-type facade (`ki-page`/`article-page`):

    cfg = {:type        \"ki\"                 ; REST path segment + identity
           :show-kind?  true                  ; render the epistemic-kind selector?
           :cancel-route (fn [lang] …)        ; where the create form's cancel/Esc goes
           :labels      {…generic-key → i18n-key…}}  ; the facade owns the wording

  Whatever varies between types is either a `cfg` value the facade chose or is derived
  from the document's own data — this ns never names a concrete type. On success both
  flows dispatch the generic `:agora/saved`, which navigates to the saved version."
  (:require
   [clojure.string                       :as str]
   [landing.agora.document.kind          :as dk]
   [landing.agora.frontend.auth          :as auth]
   [landing.agora.frontend.cite          :as cite]
   [landing.agora.frontend.document-page :as    dv
                                         :refer [byline
                                                 card-style
                                                 json-req
                                                 kind-badge
                                                 lang-badge
                                                 language-selector]]
   [landing.agora.frontend.i18n          :as i18n]
   [landing.agora.frontend.modal         :as modal]
   [landing.agora.frontend.publications  :as publications]
   [landing.agora.frontend.source        :as source]
   [landing.agora.frontend.ui-commons    :as ui]
   [re-frame.core                        :as rf]
   [reagent.core                         :as r]
   [superstructor.re-frame.fetch-fx]))

(defn- lbl
  "Resolve generic label key `k` against `labels` (a facade-supplied map of generic key →
  i18n key) in `lang`."
  [lang labels k]
  (i18n/t lang (get labels k)))

(defn kind-selector
  "A collapsible kind picker. Collapsed, it shows only the selected kind (with a ▾ hint); clicking it
  reveals every kind of `object-type` (KI kinds vs article kinds are disjoint) as clickable badges,
  the selected one highlighted. Picking one calls `on-select` with that kind and collapses again."
  [object-type selected on-select]
  (r/with-let
   [open? (r/atom false)]
   (if @open?
     (into [:div {:style {:display "flex"
                          :flex-wrap "wrap"
                          :gap "0.4em"}}]
           (for [t (dk/kind-ids-of object-type)
                 :let [current? (= t selected)]]
             ^{:key t}
             [:button {:on-click #(do (on-select t) (reset! open? false))
                       :title (name t)
                       :style {:border "none"
                               :background "transparent"
                               :padding "0.1em"
                               :cursor "pointer"
                               :border-radius "0.3em"
                               :opacity (if current? 1 0.4)
                               :box-shadow (if current? "0 0 0 2px #333" "none")}}
              [kind-badge t]]))
     [:button {:on-click #(reset! open? true)
               :title (some-> selected
                              name)
               :style {:border "none"
                       :background "transparent"
                       :padding "0.1em"
                       :cursor "pointer"
                       :border-radius "0.3em"
                       :display "inline-flex"
                       :align-items "center"
                       :gap "0.25em"}}
      [kind-badge selected]
      [:span {:style {:font-size "0.6em"
                      :color "#888"}}
       "▾"]])))

;; ===========================================================================
;; Edit an existing document → a new minor version (a fork)
;; ===========================================================================

(rf/reg-sub ::edit (fn [db _] (::edit db)))

(defn close-panels
  "Collapse the edit form and the add-consequence/add-predecessor widgets. Used by core on route
  changes (and after a save) so nothing lingers from a previous document."
  [db]
  (-> db
      (update ::edit assoc :open? false)
      (dissoc ::consequence ::predecessor)))

(rf/reg-event-db ::op-failed
                 (fn [db [_ resp]]
                   (js/console.error "[agora] operation failed:" (clj->js resp))
                   (-> db
                       (dissoc :agora/authoring-busy?)
                       (assoc-in [::new :submitting?] false)
                       (update ::edit assoc :saving? false :error resp))))

(rf/reg-event-db
 ::edit-open
 (fn [db [_ doc]]
   ;; the doc comes from JSON, so its kind is a string; the form works in keywords
   ;; (kind-selector match, doc-body dispatch) like the create flow
   (let [kind (some-> (:kind doc)
                      keyword)
         ;; kinds whose single structural input lives out of the prose (rendered in the opening): an
         ;; example/counter-example's claim, an extract's work
         target-kind? (contains? #{:illustration :counter-example :extract} kind)
         raw (cite/node-text doc)
         ;; strip that citation from the seeded body so editing never touches it and the
         ;; removed-citation warning can't fire on it — a legacy doc (input in text) migrates on save
         text (if target-kind? (cite/without-citations raw) raw)]
     (assoc db
            ::edit
            {:open? true
             :type (:type doc)
             :id (:id doc)
             :title (:title doc)
             :kind kind
             :text text
             ;; the structural input, seeded so its picker shows the current one — the resolved work
             ;; for an extract, the resolved claim for an example/counter-example
             :target (case kind
                       :extract (:work doc)
                       (:illustration :counter-example) (:target doc)
                       nil)
             ;; a work's cited author + bibliographic fields, an extract's locator —
             ;; seeded so an edit can change them (nil for other kinds)
             :author-id (:attributed-author-id doc)
             :author-name (:attributed-author doc)
             :year (:year doc)
             :editor (:editor doc)
             :url (:url doc)
             :locator (:locator doc)
             ;; citations present when editing began — to warn if an input
             ;; reference gets removed before saving.
             :orig-cites (cite/citations text)
             :saving? false
             :error nil}))))

(rf/reg-event-db ::edit-close (fn [db _] (update db ::edit assoc :open? false)))
(rf/reg-event-db ::edit-set (fn [db [_ k v]] (update db ::edit assoc k v)))

(rf/reg-event-fx ::edit-save
                 (fn [{:keys [db]} _]
                   (let [{:keys [text orig-cites]} (::edit db)
                         removed? (seq (remove (cite/citations text) orig-cites))]
                     (if removed?
                       ;; removing a citation drops an input edge — confirm first (React dialog); cancel releases the
                       ;; in-flight authoring lock set by `ensure-active`
                       {:dispatch [::modal/confirm {:message (i18n/t (i18n/current db)
                                                                     :cite/removed-warning)
                                                    :danger? true
                                                    :on-confirm [::edit-save!]
                                                    :on-cancel [::edit-save-cancel]}]}
                       {:dispatch [::edit-save!]}))))

(rf/reg-event-db ::edit-save-cancel
                 (fn [db _]
                   (-> db
                       (dissoc :agora/authoring-busy?)
                       (update ::edit assoc :saving? false))))

(rf/reg-event-fx
 ::edit-save!
 (fn [{:keys [db]} _]
   (let [{:keys [type id title kind text author-id author-name year editor url locator target]}
         (::edit db)
         pub-id (get-in db [:agora/active-publication :id])]
     {:db (update db ::edit assoc :saving? true :error nil)
      ;; an edit produces a new version, gathered as a draft in the active
      ;; publication (`:publication-id`, required by the write path)
      :fetch (json-req :post
                       (str "/agora/api/documents/" type "/" id)
                       (cond-> {:title title
                                :text text
                                :author-id author-id
                                :author-name author-name
                                :year year
                                :editor editor
                                :url url
                                :locator locator
                                :target target
                                :publication-id pub-id}
                         kind (assoc :kind kind))
                       [::saved-ok]
                       [::op-failed])})))

;; ===========================================================================
;; Create a new document (standalone form)
;; ===========================================================================

(rf/reg-sub ::new (fn [db _] (::new db)))
;; an authoring action (create/edit) is in flight — set when it starts (ensure-active), cleared on
;; its terminal success/failure. Disables the submit buttons so a repeat click can't double-fire.
(rf/reg-sub ::busy? (fn [db _] (:agora/authoring-busy? db)))
(rf/reg-event-db ::new-reset
                 (fn [db [_ {:keys [type show-kind?]}]]
                   (assoc db
                          ::new
                          {:type type
                           :show-kind? show-kind?
                           ;; seed the type's default kind (first in its display order:
                           ;; inference for KIs, explainer for articles)
                           :kind (first (dk/kind-ids-of type))})))
(rf/reg-event-db ::new-set (fn [db [_ k v]] (assoc-in db [::new k] v)))

(rf/reg-event-fx
 ::new-submit
 (fn [{:keys [db]} _]
   (let [{:keys [type
                 show-kind?
                 title
                 kind
                 lang
                 text
                 author-id
                 author-name
                 year
                 editor
                 url
                 locator
                 target]}
         (::new db)
         pub-id (get-in db [:agora/active-publication :id])]
     {:db (assoc-in db [::new :submitting?] true)
      ;; a create is a draft gathered by the active publication (`:publication-id`,
      ;; required by the write path)
      :fetch (json-req :post
                       (str "/agora/api/documents/" type)
                       (cond-> {:title title
                                :lang (or lang (i18n/current db))
                                :text text
                                :author-id author-id
                                :author-name author-name
                                :year year
                                :editor editor
                                :url url
                                :locator locator
                                :target target
                                :publication-id pub-id}
                         show-kind? (assoc :kind kind))
                       [::saved-ok]
                       [::op-failed])})))

;; Shared success path for both create and edit: ingest + navigate to the saved version.
;; `:agora/saved` is generic — it reads the type off the returned document, so there is no
;; per-type branch here or in core.
(rf/reg-event-fx ::saved-ok
                 (fn [{:keys [db]} [_ resp]]
                   (let [doc (:body resp)]
                     (if (:id doc)
                       {:db (dissoc db ::new ::publish-error :agora/authoring-busy?)
                        :dispatch [:agora/saved doc]}
                       {:db (-> db
                                (dissoc :agora/authoring-busy?)
                                (assoc-in [::new :submitting?] false)
                                (update ::edit assoc :saving? false :error resp))}))))

;; Publish a draft: promote this version, prune the lineage's intermediate drafts, then reuse
;; `::saved-ok` to navigate to the now-published document. A 422 means an input is still a draft
;; (the publish invariant) — `::publish-failed` stashes the offending inputs so the banner can
;; list them with links.
(rf/reg-event-fx ::publish
                 (fn [{:keys [db]} [_ type id]]
                   {:db (dissoc db ::publish-error)
                    :fetch (json-req :post
                                     (str "/agora/api/" type "/" id "/publish")
                                     {}
                                     [::saved-ok]
                                     [::publish-failed id])}))

(rf/reg-event-db ::publish-failed
                 (fn [db [_ id resp]]
                   (js/console.error "[agora] publish failed:" (clj->js resp))
                   (assoc db
                          ::publish-error
                          {:id id
                           :inputs (get-in resp [:body :unpublished-inputs])
                           :message (get-in resp [:body :error])})))

(rf/reg-sub ::publish-error (fn [db _] (::publish-error db)))

;; --- admin maintenance from the edit card ----------------------------------
;; Owner-only: delete the whole lineage, or keep only its latest version (compact). Both
;; target the lineage (type,name,lang,major) via the admin endpoints, then leave the editor —
;; a drop goes to discover (the document is gone), a compact returns to the (still-live)
;; document's permalink.
(rf/reg-event-fx ::admin-drop
                 (fn [_ [_ type doc-name lang major]]
                   {:fetch (json-req :post
                                     "/agora/api/admin/drop-tnr"
                                     {:type type
                                      :name doc-name
                                      :lang lang
                                      :major major}
                                     [::admin-left type doc-name lang major :drop]
                                     [::op-failed])}))

(rf/reg-event-fx ::admin-compact
                 (fn [_ [_ type doc-name lang major]]
                   {:fetch (json-req :post
                                     "/agora/api/admin/compact-tnr"
                                     {:type type
                                      :name doc-name
                                      :lang lang
                                      :major major}
                                     [::admin-left type doc-name lang major :compact]
                                     [::op-failed])}))

(rf/reg-event-fx ::admin-left
                 (fn [{:keys [db]} [_ type doc-name _lang major action]]
                   {:db (close-panels db)
                    :agora/navigate (if (= action :drop)
                                      (i18n/discover (i18n/current db))
                                      (i18n/doc-permalink (i18n/current db)
                                                          type
                                                          {:name doc-name
                                                           :major major}))}))

(defn publish-action
  "For a **draft** the current user owns, a Publish banner on the read view. Publishing clears
  the draft flag, prunes the lineage's intermediate drafts, re-pins successors, and makes the
  document resolve. Renders nothing for a published document or a viewer who isn't its owner."
  [{:keys [id draft author-id]
    doc-type :type}]
  (let [user @(rf/subscribe [::auth/user])]
    (when (and draft (= (:id user) author-id))
      (let [lang @(rf/subscribe [::i18n/lang])
            err @(rf/subscribe [::publish-error])
            blocked (when (= (:id err) id) (:inputs err))]
        [:div {:style {:margin-bottom "0.9em"}}
         [:div {:style {:display "flex"
                        :align-items "center"
                        :justify-content "space-between"
                        :gap "0.7em"
                        :flex-wrap "wrap"
                        :padding "0.5em 0.8em"
                        :background "#fdf6ec"
                        :border "1px dashed #b98a3e"
                        :border-radius "0.4em"}}
          [:span {:style {:font-size "0.88em"
                          :color "#8a5709"}}
           (str "✎ " (i18n/t lang :ki/draft-notice))]
          [:button {:on-click #(rf/dispatch [::publish doc-type id])
                    :style {:padding "0.4em 1em"
                            :border "none"
                            :background "#2b8a3e"
                            :color "#fff"
                            :border-radius "0.3em"
                            :cursor "pointer"
                            :font-weight 600}}
           (i18n/t lang :ki/publish)]]
         ;; publish invariant: a public node may not depend on a draft input
         (when (seq blocked)
           [:div {:style {:margin-top "0.5em"
                          :padding "0.5em 0.8em"
                          :background "#fdecec"
                          :border "1px solid #d9534f"
                          :border-radius "0.4em"
                          :font-size "0.85em"
                          :color "#8a1f1f"}}
            [:div {:style {:margin-bottom "0.3em"
                           :font-weight 600}}
             (i18n/t lang :ki/publish-blocked)]
            (into [:ul {:style {:margin 0
                                :padding-left "1.2em"}}]
                  (map (fn [{:keys [type id title]
                             nm :name}]
                         [:li
                          [:a {:href (i18n/doc-url lang type id)
                               :style {:color "#8a1f1f"
                                       :text-decoration "underline"}}
                           (or title nm)]]))
                  blocked)])]))))

;; --- inline successor: spawn a consequence without leaving the page --------
;; A *consequence* of KI X is a new KI that cites X as its input — the `[[ki:X]]` edge lives in
;; the new KI's text, not X's, so creating one never modifies X and any logged-in user can do it
;; while reading. It is created as a **draft** (hidden until published, so it does not yet appear
;; in X's successors row); the widget lists the drafts it just made so you can flesh them out
;; later. KI-only, because citations target KIs.

(rf/reg-sub ::consequence (fn [db _] (::consequence db)))

(rf/reg-event-db ::consequence-toggle
                 (fn [db [_ open?]]
                   (update db
                           ::consequence
                           merge
                           {:open? open?
                            :title ""
                            :error nil})))

(rf/reg-event-db ::consequence-set-title (fn [db [_ v]] (assoc-in db [::consequence :title] v)))

(rf/reg-event-fx
 ::consequence-create
 (fn [{:keys [db]} [_ parent]]
   (let [title (get-in db [::consequence :title])]
     (when-not (str/blank? title)
       {:db (assoc-in db [::consequence :creating?] true)
        :fetch (json-req
                :post
                "/agora/api/documents/ki"
                ;; seed the new KI's text with a citation of the parent → parent becomes
                ;; its input. Same content language as the parent so the edge resolves. A draft
                ;; gathered by the active publication (`:publication-id`, required by the write path).
                {:title title
                 ;; the consequence's kind depends on the parent's — a work yields an extract, an
                 ;; extract an appropriation, else an inference (see `dk/kind-consequence`)
                 :kind (dk/kind-consequence (:kind parent))
                 :lang (:lang parent)
                 :publication-id (get-in db [:agora/active-publication :id])
                 :text
                 (str "[[" (or (:type parent) "ki") ":" (:name parent) "@" (:major parent) "]]")}
                [::consequence-created]
                [::consequence-failed])}))))

;; `core/refetch-scoped` by a fully-qualified keyword — `edit` can't require `core` (it would cycle)
(def ^:private refetch-scoped :landing.agora.frontend.core/refetch-scoped)

(rf/reg-event-fx ::consequence-created
                 (fn [{:keys [db]} _]
                   ;; the consequence is a draft citing this KI; re-fetch the page so it shows in the
                   ;; publication-scoped successors row like any other successor, not a separate list
                   {:db (update db ::consequence assoc :creating? false :title "" :error nil)
                    :dispatch [refetch-scoped]}))

(rf/reg-event-db ::consequence-failed
                 (fn [db [_ resp]]
                   (js/console.error "[agora] consequence create failed:" (clj->js resp))
                   (update db ::consequence assoc :creating? false :error true)))

(defn add-consequence
  "On a KI read view, a logged-in user spawns a **consequence** — a new draft KI that takes this
  KI as its input — without leaving the page (quick-capture; refine it later from your drafts).
  Renders nothing on articles (citations target KIs) or for anonymous viewers."
  [{doc-type :type
    :as doc}]
  (let [user @(rf/subscribe [::auth/user])]
    (when (and (= doc-type "ki") (:id user))
      (let [lang @(rf/subscribe [::i18n/lang])
            st @(rf/subscribe [::consequence])]
        [:div {:style {:display "flex"
                       :flex-direction "column"
                       :align-items "center"
                       :gap "0.5em"
                       :margin-top "0.4em"}}
         (if (:open? st)
           [:div {:style {:display "flex"
                          :gap "0.4em"
                          :flex-wrap "wrap"
                          :justify-content "center"
                          :max-width "30em"}}
            [ui/composed-field {:type "text"
                                :placeholder (i18n/t lang :ki/consequence-ph)
                                :value (:title st)
                                :auto-focus true
                                :on-text #(rf/dispatch [::consequence-set-title %])
                                :on-key-down #(when (= "Enter" (.-key %))
                                                (rf/dispatch [::consequence-create doc]))
                                :style {:padding "0.45em 0.6em"
                                        :border "1px solid #ccc"
                                        :border-radius "0.3em"
                                        :min-width "18em"
                                        :font-size "0.9em"}}]
            [:button {:on-click #(rf/dispatch [::consequence-create doc])
                      :disabled (or (:creating? st) (str/blank? (:title st)))
                      :style {:padding "0.45em 1em"
                              :border "none"
                              :background "#2b8a3e"
                              :color "#fff"
                              :border-radius "0.3em"
                              :cursor "pointer"
                              :font-weight 600
                              :font-size "0.9em"}}
             (i18n/t lang :ki/consequence-create)]
            [:button {:on-click #(rf/dispatch [::consequence-toggle false])
                      :style {:padding "0.45em 0.7em"
                              :border "1px solid #ccc"
                              :background "#fff"
                              :border-radius "0.3em"
                              :cursor "pointer"
                              :font-size "0.9em"}}
             "✕"]]
           [:button {:on-click #(rf/dispatch [::consequence-toggle true])
                     :style {:padding "0.4em 0.9em"
                             :border "1px dashed #b98a3e"
                             :background "#fff"
                             :color "#8a5709"
                             :border-radius "0.3em"
                             :cursor "pointer"
                             :font-size "0.9em"}}
            (str "↳ " (i18n/t lang :ki/add-consequence))])
         (when (:error st)
           [:div {:style {:color "#8a1f1f"
                          :font-size "0.82em"}}
            (i18n/t lang :ki/consequence-failed)])]))))

;; --- inline predecessor: spawn an upstream input without leaving the page ---
;; A *predecessor* of KI X is a new KI that X cites as its input. Unlike a consequence (which only
;; creates the downstream KI and leaves X untouched), wiring a predecessor **edits X** — the new KI
;; is created empty upstream, then X gets a new minor whose text cites it, so it shows in X's inputs.
;; Two chained writes, both in the active publication.

(rf/reg-sub ::predecessor (fn [db _] (::predecessor db)))

(rf/reg-event-db ::predecessor-toggle
                 (fn [db [_ open?]]
                   (update db
                           ::predecessor
                           merge
                           {:open? open?
                            :title ""
                            :error nil})))

(rf/reg-event-db ::predecessor-set-title (fn [db [_ v]] (assoc-in db [::predecessor :title] v)))

(rf/reg-event-fx ::predecessor-create
                 (fn [{:keys [db]} [_ this]]
                   (let [title (get-in db [::predecessor :title])]
                     (when-not (str/blank? title)
                       {:db (-> db
                                (assoc-in [::predecessor :creating?] true)
                                ;; stash the doc to wire once the upstream KI exists
                                (assoc-in [::predecessor :parent] this))
                        :fetch (json-req :post
                                         "/agora/api/documents/ki"
                                         ;; the upstream KI is created empty (fleshed out later); THIS doc will cite it. Same
                                         ;; content language + publication so the edge resolves.
                                         {:title title
                                          :kind :inference
                                          :lang (:lang this)
                                          :publication-id (get-in db
                                                                  [:agora/active-publication :id])
                                          :text ""}
                                         [::predecessor-created]
                                         [::predecessor-failed])}))))

(rf/reg-event-fx ::predecessor-created
                 (fn [{:keys [db]} [_ resp]]
                   ;; the upstream KI now exists — wire it in by editing THIS doc to cite it (a new minor), so the new
                   ;; KI shows in this doc's inputs like any other predecessor
                   (let [this (get-in db [::predecessor :parent])
                         pred (:body resp)
                         cite (str "[[ki:" (:name pred) "@" (:major pred) "]]")
                         t (or (:text this) "")
                         text (if (str/blank? t) cite (str t "\n\n" cite))]
                     {:fetch (json-req :post
                                       (str "/agora/api/documents/ki/" (:id this))
                                       {:title (:title this)
                                        :text text
                                        :kind (:kind this)
                                        :publication-id (get-in db [:agora/active-publication :id])}
                                       [::predecessor-wired]
                                       [::predecessor-failed])})))

(rf/reg-event-fx
 ::predecessor-wired
 (fn [{:keys [db]} [_ resp]]
   ;; wiring created a new minor of this doc — navigate to it (like a normal edit) so the reader
   ;; lands on the version that shows the new input
   {:db (update db ::predecessor assoc :creating? false :title "" :error nil :parent nil)
    :dispatch [:agora/saved (:body resp)]}))

(rf/reg-event-db ::predecessor-failed
                 (fn [db [_ resp]]
                   (js/console.error "[agora] predecessor create failed:" (clj->js resp))
                   (update db ::predecessor assoc :creating? false :error true)))

(defn add-predecessor
  "On a KI read view, a logged-in user spawns a **predecessor** — a new upstream KI that this KI
  takes as an input — without leaving the page. Mirrors `add-consequence`, but wiring it edits this
  KI (a new minor citing the upstream one). Renders nothing on articles or for anonymous viewers."
  [{doc-type :type
    :as doc}]
  (let [user @(rf/subscribe [::auth/user])]
    (when (and (= doc-type "ki") (:id user))
      (let [lang @(rf/subscribe [::i18n/lang])
            st @(rf/subscribe [::predecessor])]
        [:div {:style {:display "flex"
                       :flex-direction "column"
                       :align-items "center"
                       :gap "0.5em"
                       :margin-top "0.4em"}}
         (if (:open? st)
           [:div {:style {:display "flex"
                          :gap "0.4em"
                          :flex-wrap "wrap"
                          :justify-content "center"
                          :max-width "30em"}}
            [ui/composed-field {:type "text"
                                :placeholder (i18n/t lang :ki/predecessor-ph)
                                :value (:title st)
                                :auto-focus true
                                :on-text #(rf/dispatch [::predecessor-set-title %])
                                :on-key-down #(when (= "Enter" (.-key %))
                                                (rf/dispatch [::predecessor-create doc]))
                                :style {:padding "0.45em 0.6em"
                                        :border "1px solid #ccc"
                                        :border-radius "0.3em"
                                        :min-width "18em"
                                        :font-size "0.9em"}}]
            [:button {:on-click #(rf/dispatch [::predecessor-create doc])
                      :disabled (or (:creating? st) (str/blank? (:title st)))
                      :style {:padding "0.45em 1em"
                              :border "none"
                              :background "#2b8a3e"
                              :color "#fff"
                              :border-radius "0.3em"
                              :cursor "pointer"
                              :font-weight 600
                              :font-size "0.9em"}}
             (i18n/t lang :ki/consequence-create)]
            [:button {:on-click #(rf/dispatch [::predecessor-toggle false])
                      :style {:padding "0.45em 0.7em"
                              :border "1px solid #ccc"
                              :background "#fff"
                              :border-radius "0.3em"
                              :cursor "pointer"
                              :font-size "0.9em"}}
             "✕"]]
           [:button {:on-click #(rf/dispatch [::predecessor-toggle true])
                     :style {:padding "0.4em 0.9em"
                             :border "1px dashed #b98a3e"
                             :background "#fff"
                             :color "#8a5709"
                             :border-radius "0.3em"
                             :cursor "pointer"
                             :font-size "0.9em"}}
            (str "↰ " (i18n/t lang :ki/add-predecessor))])
         (when (:error st)
           [:div {:style {:color "#8a1f1f"
                          :font-size "0.82em"}}
            (i18n/t lang :ki/consequence-failed)])]))))

(defn admin-actions
  "Owner-only maintenance for a document, shown on the **read view** so an admin acts without
  entering edit mode: *keep last version* (compact — drop earlier versions) or *delete* the
  whole lineage. `doc` supplies the identity (`:type :name :lang :major`). Renders nothing for
  non-admins."
  [{doc-type :type
    doc-name :name
    doc-lang :lang
    :keys [major]}]
  (when @(rf/subscribe [::auth/admin?])
    (let [lang @(rf/subscribe [::i18n/lang])
          btn (fn [border-color danger? confirm-key label-key event]
                [:button {:on-click #(rf/dispatch [::modal/confirm
                                                   {:message (i18n/t lang confirm-key)
                                                    :danger? danger?
                                                    :on-confirm
                                                    [event doc-type doc-name doc-lang major]}])
                          :style {:padding "0.3em 0.7em"
                                  :border (str "1px solid " border-color)
                                  :background "#fff"
                                  :color border-color
                                  :border-radius "0.3em"
                                  :cursor "pointer"
                                  :font-size "0.85em"}}
                 (i18n/t lang label-key)])]
      [:div {:style {:display "flex"
                     :gap "0.5em"
                     :justify-content "flex-end"
                     :margin-top "1.2em"}}
       (btn "#b9770e" false :edit/keep-last-confirm :edit/keep-last ::admin-compact)
       (btn "#c92a2a" true :edit/delete-confirm :edit/delete ::admin-drop)])))

;; ===========================================================================
;; Components
;; ===========================================================================

(def ^:private label-style
  {:font-size "0.8em"
   :color "#555"
   :margin-bottom "0.3em"})

(def ^:private title-input-style
  {:width "100%"
   :box-sizing "border-box"
   :padding "0.5em"
   :font-family "inherit"
   :font-size "0.95em"
   :border "1px solid #ccc"
   :border-radius "0.3em"
   :margin-bottom "0.8em"})

(defn- prefix-label
  "Read-only preview of the kind-guided opening the stored body will follow, derived live
  from the form's `kind`/`title` and the authoring user. Nothing for the free-form `inference`
  kind — so the author writes only the body, and the grammar is enforced without being stored."
  [{:keys [kind title]} author-name lang]
  (when-let [p (dk/statement-prefix-of {:kind kind
                                        :title title
                                        :author author-name}
                                       (keyword lang))]
    [:div {:style {:font-size "0.9em"
                   :color "#8a7a55"
                   :font-style "italic"
                   :margin-bottom "0.3em"}}
     p]))

(defn- locator-field
  "The extract's locator (page / chapter / verse) — a scalar shown under the quote."
  [value on-text]
  (let [lang @(rf/subscribe [::i18n/lang])]
    [ui/composed-field {:type "text"
                        :value (or value "")
                        :placeholder (i18n/t lang :extract/locator-ph)
                        :style {:width "100%"
                                :box-sizing "border-box"
                                :margin-top "0.4em"
                                :padding "0.45em"
                                :font-size "0.9em"
                                :border "1px solid #ccc"
                                :border-radius "0.3em"}
                        :on-text on-text}]))

(defn- target-picker
  "Pick the single node an example / counter-example references (any KI). Sets `:target` on the form —
  kept out of the prose, so editing the body never touches it. Shows the current target with a clear ✕,
  else a search over the published + in-publication KIs."
  [value set-target! pub-id]
  (r/with-let
   [q (r/atom "") results (r/atom [])]
   (let [lang @(rf/subscribe [::i18n/lang])
         run! (fn [v]
                (reset! q v)
                (if (str/blank? v)
                  (reset! results [])
                  (-> (js/fetch (str "/agora/api/documents/ki?lang="
                                     (name lang)
                                     "&q="
                                     (js/encodeURIComponent v)
                                     (when pub-id
                                       (str "&publication=" (js/encodeURIComponent pub-id))))
                                #js {:headers #js {"Accept" "application/json"}})
                      (.then #(.json %))
                      (.then #(reset! results (js->clj % :keywordize-keys true)))
                      (.catch (fn [_] (reset! results []))))))]
     [:div {:style {:margin "0.5em 0"}}
      [:div {:style {:font-size "0.8em"
                     :color "#8a7a55"
                     :font-weight 700
                     :margin-bottom "0.3em"}}
       (i18n/t lang :target/label)]
      (if value
        [:div {:style {:display "inline-flex"
                       :align-items "center"
                       :gap "0.5em"
                       :border "1px solid #b9770e"
                       :border-radius "0.3em"
                       :padding "0.3em 0.6em"}}
         [:span {:style {:font-weight 600}}
          (:title value)]
         [:button {:on-click #(set-target! nil)
                   :title (i18n/t lang :target/change)
                   :style {:border "none"
                           :background "transparent"
                           :color "#c92a2a"
                           :cursor "pointer"
                           :font-size "0.9em"}}
          "✕"]]
        [:<>
         [ui/composed-field {:type "text"
                             :value @q
                             :placeholder (i18n/t lang :target/search-ph)
                             :on-text run!
                             :style {:width "100%"
                                     :box-sizing "border-box"
                                     :padding "0.5em 0.6em"
                                     :border "1px solid #ccc"
                                     :border-radius "0.3em"}}]
         (when (seq @results)
           (into [:ul {:style {:list-style "none"
                               :margin "0.3em 0 0"
                               :padding 0
                               :border "1px solid #eee"
                               :border-radius "0.3em"
                               :max-height "12em"
                               :overflow-y "auto"}}]
                 (for [k @results]
                   ^{:key (:id k)}
                   [:li
                    [:button {:on-click #(do (set-target!
                                              (select-keys k [:type :name :lang :major :title]))
                                             (reset! q "")
                                             (reset! results []))
                              :style {:display "block"
                                      :width "100%"
                                      :text-align "left"
                                      :border "none"
                                      :background "transparent"
                                      :cursor "pointer"
                                      :padding "0.4em 0.6em"
                                      :font-size "0.9em"}}
                     (:title k)]])))])])))

(defn- work-target-picker
  "Pick the single bibliographic work an extract draws from, via the work modal (pick or create a
  work). Sets `:target` on the form — kept out of the prose, exactly like the example/counter-example
  target. Shows the current work with a clear ✕."
  [value set-target! pub-id]
  (r/with-let
   [open? (r/atom false)]
   (let [lang @(rf/subscribe [::i18n/lang])]
     [:div {:style {:margin "0.5em 0"}}
      [:div {:style {:font-size "0.8em"
                     :color "#8a7a55"
                     :font-weight 700
                     :margin-bottom "0.3em"}}
       (i18n/t lang :extract/work-label)]
      (if value
        [:div {:style {:display "inline-flex"
                       :align-items "center"
                       :gap "0.5em"
                       :border "1px solid #b9770e"
                       :border-radius "0.3em"
                       :padding "0.3em 0.6em"}}
         [:span {:style {:font-weight 600}}
          (:title value)]
         [:button {:on-click #(set-target! nil)
                   :title (i18n/t lang :target/change)
                   :style {:border "none"
                           :background "transparent"
                           :color "#c92a2a"
                           :cursor "pointer"
                           :font-size "0.9em"}}
          "✕"]]
        [:button {:on-click #(reset! open? true)
                  :style {:border "1px dashed #b9770e"
                          :background "transparent"
                          :color "#b9770e"
                          :border-radius "0.3em"
                          :padding "0.35em 0.8em"
                          :cursor "pointer"
                          :font-size "0.88em"}}
         (str "🔎 " (i18n/t lang :extract/cite-work))])
      (when @open?
        [source/work-modal
         (fn [w]
           (set-target! {:type "ki"
                         :name (:name w)
                         :lang (:lang w)
                         :major (:major w)
                         :title (:title w)})
           (reset! open? false))
         #(reset! open? false)
         lang
         pub-id])])))

(defn- doc-body
  "The kind-appropriate authoring body: a `work`'s bibliographic fields; an `extract`'s work-picker +
  verbatim quote + locator; an example / counter-example's target-picker + plain prose. In every one of
  the last three the single structural input is a field (a picker), not a body citation, so the prose
  is a plain textarea. Otherwise the standard citation editor over the prose. `set-fn` is
  `(set-fn key value)` on the form state; `text-ph`/`self-name`/`pub-id` drive the editors."
  [{:keys [kind text target author-id author-name year editor url locator]}
   set-fn
   text-ph
   self-name
   pub-id]
  (cond
    (= kind :work) [source/work-fields {:author-id author-id
                                        :author-name author-name
                                        :year year
                                        :editor editor
                                        :url url}
                    set-fn]
    (= kind :extract) [:<>
                       [work-target-picker target #(set-fn :target %) pub-id]
                       ;; the quote cites nothing but the work (a field), so a plain textarea — no citation box
                       [cite/citation-editor text #(set-fn :text %) text-ph self-name false pub-id]
                       [locator-field locator #(set-fn :locator %)]]
    (contains? #{:illustration :counter-example} kind)
    [:<>
     [target-picker target #(set-fn :target %) pub-id]
     ;; the prose cites nothing but the target, so no citation box — a plain textarea
     [cite/citation-editor text #(set-fn :text %) text-ph self-name false pub-id]]
    :else [cite/citation-editor
           text
           #(set-fn :text %)
           text-ph
           self-name
           (dk/kind-allows-inputs? (or kind :inference))
           pub-id]))

(defn edit-form
  "The central card in edit mode (a new minor): metadata row (kind selector when
  `:show-kind?`, language badge, next-version tag), editable title, byline, the citation
  editor over the prose, and the source editor — all in place on the card. `cfg` is the
  facade's config (`:labels`, `:show-kind?`)."
  [{doc-name :name
    doc-lang :lang
    :keys [major minor published-at author]}
   {:keys [show-kind? labels]
    object-type :type}]
  (let [{:keys [title kind text target author-id author-name year editor url locator saving? error]}
        @(rf/subscribe [::edit])
        lang @(rf/subscribe [::i18n/lang])
        user @(rf/subscribe [::auth/user])
        busy? @(rf/subscribe [::busy?])]
    [:article {:style card-style}
     [:div {:style {:display "flex"
                    :align-items "center"
                    :gap "0.75em"
                    :margin-bottom "0.6em"}}
      (when show-kind? [kind-selector object-type kind #(rf/dispatch [::edit-set :kind %])])
      [lang-badge doc-lang]
      [:span {:style {:color "#888"
                      :font-size "0.8em"
                      :font-family "monospace"}}
       (str "v" major "." minor " " (i18n/t lang :form/next))]]
     [ui/composed-field {:type "text"
                         :value (or title "")
                         :on-text #(rf/dispatch [::edit-set :title %])
                         :style {:width "100%"
                                 :box-sizing "border-box"
                                 :margin "0.2em 0 0.1em"
                                 :padding "0.35em 0.4em"
                                 :font-size "1.3em"
                                 :font-weight 700
                                 :font-family "inherit"
                                 :border "1px solid #eee"
                                 :border-radius "0.3em"}}]
     [byline author published-at]
     [prefix-label {:kind kind
                    :title title}
      (:display-name user)
      ;; the doc's content language (not the interface lang) — the prefix is part of the text
      doc-lang]
     [doc-body {:kind kind
                :text text
                :target target
                :author-id author-id
                :author-name author-name
                :year year
                :editor editor
                :url url
                :locator locator}
      (fn [k v] (rf/dispatch [::edit-set k v]))
      (lbl lang labels :text-ph)
      doc-name
      (:id @(rf/subscribe [::publications/active]))]
     (when error
       [:div {:style {:color "#c92a2a"
                      :font-size "0.85em"
                      :margin-top "0.5em"}}
        (lbl lang labels :save-failed)])
     [:div {:style {:display "flex"
                    :gap "0.5em"
                    :margin-top "0.7em"}}
      ;; ensure an active publication (auto-create if none), then save → new version in it
      [:button {:on-click (when-not busy?
                            #(rf/dispatch [::publications/ensure-active [::edit-save]]))
                :disabled (boolean busy?)
                :style {:padding "0.4em 0.9em"
                        :border "none"
                        :background "#b9770e"
                        :color "#fff"
                        :border-radius "0.3em"
                        :cursor (if busy? "default" "pointer")}}
       (if (or saving? busy?) (lbl lang labels :saving) (lbl lang labels :save))]
      [:button {:on-click #(rf/dispatch [::edit-close])
                :style {:padding "0.4em 0.9em"
                        :border "1px solid #ccc"
                        :background "#fff"
                        :border-radius "0.3em"
                        :cursor "pointer"}}
       (lbl lang labels :cancel)]]]))

(defn create-form
  "Standalone create form driven entirely by the facade's `cfg` (`:type`, `:show-kind?`,
  `:cancel-route`, `:labels`): title, an optional kind selector, language, the citation
  editor over the prose, and its source. `with-let` resets the shared `::new` state on
  mount so switching types never carries stale fields."
  [{:keys [show-kind? cancel-route labels]
    object-type :type
    :as cfg}]
  (r/with-let
   [_ (rf/dispatch-sync [::new-reset cfg])]
   (let [{:keys [title kind text target author-id author-name year editor url locator submitting?]
          form-lang :lang}
         @(rf/subscribe [::new])
         user @(rf/subscribe [::auth/user])
         lang @(rf/subscribe [::i18n/lang])
         cancel-url (cancel-route lang)
         busy? @(rf/subscribe [::busy?])
         ;; the identity slug is derived from the title server-side, so it isn't asked. A work has
         ;; no prose — it is valid on title + cited author; every other kind needs its text.
         blank? (if (= kind :work)
                  (or (str/blank? title) (nil? author-id))
                  (or (str/blank? title) (str/blank? text)))]
     [:div {:style (assoc card-style :margin "1.5em auto")}
      [ui/on-escape #(rf/dispatch [:agora/cancel-new cancel-url])]
      [:h1 {:style {:font-size "1.3em"
                    :margin "0 0 0.8em"}}
       (lbl lang labels :new-title)]
      [:div {:style label-style}
       (lbl lang labels :title)]
      [ui/composed-field {:type "text"
                          :placeholder (lbl lang labels :title-ph)
                          :value (or title "")
                          :on-text #(rf/dispatch [::new-set :title %])
                          :style title-input-style}]
      (when show-kind?
        [:<>
         [:div {:style label-style}
          (i18n/t lang :form/type)]
         [:div {:style {:margin-bottom "0.8em"}}
          [kind-selector object-type kind #(rf/dispatch [::new-set :kind %])]]])
      [:div {:style label-style}
       (i18n/t lang :form/language)]
      [:div {:style {:margin-bottom "0.8em"}}
       [language-selector (or form-lang lang) #(rf/dispatch [::new-set :lang %])]]
      (when-not (= kind :work)
        [:div {:style label-style}
         (lbl lang labels :text)])
      [prefix-label {:kind kind
                     :title title}
       (:display-name user)
       ;; the content language being authored (the selected form language), not the interface
       (or form-lang lang)]
      [doc-body {:kind kind
                 :text text
                 :target target
                 :author-id author-id
                 :author-name author-name
                 :year year
                 :editor editor
                 :url url
                 :locator locator}
       (fn [k v] (rf/dispatch [::new-set k v]))
       (lbl lang labels :text-ph)
       nil
       (:id @(rf/subscribe [::publications/active]))]
      [:div {:style {:display "flex"
                     :gap "0.5em"
                     :margin-top "0.9em"}}
       [:button {:on-click (cond
                             (not user) #(rf/dispatch [::auth/open :login])
                             (or blank? busy?) nil
                             ;; ensure an active publication (auto-create if none), then create
                             :else #(rf/dispatch [::publications/ensure-active [::new-submit]]))
                 :disabled (boolean (and user (or blank? busy?)))
                 :style {:padding "0.4em 0.9em"
                         :border "none"
                         :background "#b9770e"
                         :color "#fff"
                         :border-radius "0.3em"
                         :opacity (if user 1 0.7)
                         :cursor (if (and user (or blank? busy?)) "default" "pointer")}}
        (cond
          (not user) (lbl lang labels :login)
          (or submitting? busy?) (lbl lang labels :creating)
          :else (lbl lang labels :create))]
       [:a {:href cancel-url
            :style {:padding "0.4em 0.9em"
                    :border "1px solid #ccc"
                    :background "#fff"
                    :border-radius "0.3em"
                    :text-decoration "none"
                    :color "#444"}}
        (lbl lang labels :cancel)]]])))
