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
  "Every kind of `object-type` (KI kinds vs article kinds are disjoint sets) as a clickable
  badge; the selected one highlighted, the others dimmed. Calls `on-select` with the chosen
  kind string."
  [object-type selected on-select]
  (into [:div {:style {:display "flex"
                       :flex-wrap "wrap"
                       :gap "0.4em"}}]
        ;; `dk/kind-ids-of` is the canonical per-type set as keywords; the DB/API represent
        ;; the kind as a string, so map to `(name kw)` at this boundary.
        (for [t (map name (dk/kind-ids-of object-type))
              :let [current? (= t selected)]]
          ^{:key t}
          [:button {:on-click #(on-select t)
                    :title t
                    :style {:border "none"
                            :background "transparent"
                            :padding "0.1em"
                            :cursor "pointer"
                            :border-radius "0.3em"
                            :opacity (if current? 1 0.35)
                            :box-shadow (if current? "0 0 0 2px #333" "none")}}
           [kind-badge t]])))

;; ===========================================================================
;; Edit an existing document → a new minor version (a fork)
;; ===========================================================================

(rf/reg-sub ::edit (fn [db _] (::edit db)))

(defn close-panels
  "Collapse the edit form and the add-consequence widget. Used by core on route changes (and
  after a save) so nothing lingers from a previous document."
  [db]
  (-> db
      (update ::edit assoc :open? false)
      (dissoc ::consequence)))

(rf/reg-event-db ::op-failed
                 (fn [db [_ resp]]
                   (js/console.error "[agora] operation failed:" (clj->js resp))
                   (update db ::edit assoc :saving? false :error resp)))

(rf/reg-event-db ::edit-open
                 (fn [db [_ doc]]
                   (assoc db
                          ::edit
                          {:open? true
                           :type (:type doc)
                           :id (:id doc)
                           :title (:title doc)
                           :kind (:kind doc)
                           :text (cite/node-text doc)
                           :source (:source doc)
                           ;; the cited source-KIs (edge-only inputs) — resubmitted on save
                           :cites (vec (:cites doc))
                           ;; citations present when editing began — to warn if an input
                           ;; reference gets removed before saving.
                           :orig-cites (cite/citations (cite/node-text doc))
                           :saving? false
                           :error nil})))

(rf/reg-event-db ::edit-close (fn [db _] (update db ::edit assoc :open? false)))
(rf/reg-event-db ::edit-set (fn [db [_ k v]] (update db ::edit assoc k v)))

(rf/reg-event-fx ::edit-save
                 (fn [{:keys [db]} _]
                   (let [{:keys [type id title kind text source cites orig-cites]} (::edit db)
                         removed? (seq (remove (cite/citations text) orig-cites))]
                     (if (and removed?
                              (not (js/confirm (i18n/t (i18n/current db) :cite/removed-warning))))
                       {}
                       {:db (update db ::edit assoc :saving? true :error nil)
                        :fetch (json-req :post
                                         (str "/agora/api/" type "/" id "/edit")
                                         ;; every document now carries a kind (KI epistemic /
                                         ;; article rhetorical); the guard is belt-and-braces
                                         (cond-> {:title title
                                                  :text text
                                                  :source (source/strip-source source)
                                                  :cites (source/strip-cites cites)}
                                           kind (assoc :kind kind))
                                         [::saved-ok]
                                         [::op-failed])}))))

;; ===========================================================================
;; Create a new document (standalone form)
;; ===========================================================================

(rf/reg-sub ::new (fn [db _] (::new db)))
(rf/reg-event-db ::new-reset
                 (fn [db [_ {:keys [type show-kind?]}]]
                   (assoc db
                          ::new
                          {:type type
                           :show-kind? show-kind?
                           ;; seed the type's default kind (first in its display order:
                           ;; inference for KIs, explainer for articles)
                           :kind (name (first (dk/kind-ids-of type)))})))
(rf/reg-event-db ::new-set (fn [db [_ k v]] (assoc-in db [::new k] v)))

(rf/reg-event-fx ::new-submit
                 (fn [{:keys [db]} _]
                   (let [{:keys [type show-kind? title kind lang text source cites]} (::new db)]
                     {:db (assoc-in db [::new :submitting?] true)
                      :fetch (json-req :post
                                       (str "/agora/api/" type)
                                       (cond-> {:title title
                                                :lang (or lang (i18n/current db))
                                                :text text
                                                :source (source/strip-source source)
                                                :cites (source/strip-cites cites)}
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
                       {:db (dissoc db ::new ::publish-error)
                        :dispatch [:agora/saved doc]}
                       {:db (update db ::edit assoc :saving? false :error resp)}))))

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
                "/agora/api/ki"
                ;; seed the new KI's text with a citation of the parent → parent becomes
                ;; its input. Same content language as the parent so the edge resolves.
                {:title title
                 :kind "inference"
                 :lang (:lang parent)
                 :text
                 (str "[[" (or (:type parent) "ki") ":" (:name parent) "@" (:major parent) "]]")}
                [::consequence-created]
                [::consequence-failed])}))))

(rf/reg-event-db ::consequence-created
                 (fn [db [_ resp]]
                   (-> db
                       (update ::consequence assoc :creating? false :title "" :error nil)
                       (update-in [::consequence :created] (fnil conj []) (:body resp)))))

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
            (i18n/t lang :ki/consequence-failed)])
         ;; drafts just created — hidden from the successors row until published, so surfaced here
         (when (seq (:created st))
           [:div {:style {:display "flex"
                          :flex-wrap "wrap"
                          :gap "0.4em"
                          :justify-content "center"
                          :font-size "0.82em"}}
            (for [y (:created st)]
              ^{:key (:id y)}
              [:a {:href (i18n/doc-url lang "ki" (:id y))
                   :style {:padding "0.2em 0.6em"
                           :background "#fdf6ec"
                           :border "1px dashed #b98a3e"
                           :border-radius "1em"
                           :color "#8a5709"
                           :text-decoration "none"}}
               (str "✎ " (or (:title y) (i18n/t lang :ki/draft)))])])]))))

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
          btn (fn [border-color confirm-key label-key event]
                [:button {:on-click #(when (js/confirm (i18n/t lang confirm-key))
                                       (rf/dispatch [event doc-type doc-name doc-lang major]))
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
       (btn "#b9770e" :edit/keep-last-confirm :edit/keep-last ::admin-compact)
       (btn "#c92a2a" :edit/delete-confirm :edit/delete ::admin-drop)])))

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
  from the form's `kind`/`title`/`source` and the authoring user (the source's author when a
  source is set, else the user). Nothing for the free-form `inference` kind — so the author
  writes only the body, and the grammar is enforced without being stored."
  [{:keys [kind title source]} author-name lang]
  (when-let [p (dk/statement-prefix-of {:kind kind
                                        :title title
                                        :source source
                                        :author author-name}
                                       lang)]
    [:div {:style {:font-size "0.9em"
                   :color "#8a7a55"
                   :font-style "italic"
                   :margin-bottom "0.3em"}}
     p]))

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
  (let [{:keys [title kind text source cites saving? error]} @(rf/subscribe [::edit])
        lang @(rf/subscribe [::i18n/lang])
        user @(rf/subscribe [::auth/user])]
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
                    :title title
                    :source source
                    :cites cites}
      (:display-name user)
      ;; the doc's content language (not the interface lang) — the prefix is part of the text
      doc-lang]
     [cite/citation-editor
      text
      #(rf/dispatch [::edit-set :text %])
      (lbl lang labels :text-ph)
      doc-name
      (dk/kind-allows-inputs? kind)
      #(rf/dispatch [::edit-set :cites (source/add-cite cites %)])]
     [source/cites-list cites #(rf/dispatch [::edit-set :cites %])]
     ;; only a source-KI carries a `:source` (a reference to its shared work) — offer the
     ;; source picker just for that kind; other KIs relate to sources by *citing* them (above)
     (when (= kind "source")
       [:div {:style {:margin-top "0.8em"}}
        [source/source-editor source #(rf/dispatch [::edit-set :source %])]])
     (when error
       [:div {:style {:color "#c92a2a"
                      :font-size "0.85em"
                      :margin-top "0.5em"}}
        (lbl lang labels :save-failed)])
     [:div {:style {:display "flex"
                    :gap "0.5em"
                    :margin-top "0.7em"}}
      [:button {:on-click #(rf/dispatch [::edit-save])
                :disabled (boolean saving?)
                :style {:padding "0.4em 0.9em"
                        :border "none"
                        :background "#b9770e"
                        :color "#fff"
                        :border-radius "0.3em"
                        :cursor (if saving? "default" "pointer")}}
       (if saving? (lbl lang labels :saving) (lbl lang labels :save))]
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
   (let [{:keys [title kind text source cites submitting?]
          form-lang :lang}
         @(rf/subscribe [::new])
         user @(rf/subscribe [::auth/user])
         lang @(rf/subscribe [::i18n/lang])
         cancel-url (cancel-route lang)
         ;; the identity slug is derived from the title server-side, so it isn't asked
         blank? (or (str/blank? title) (str/blank? text))]
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
      [:div {:style label-style}
       (lbl lang labels :text)]
      [prefix-label {:kind kind
                     :title title
                     :source source
                     :cites cites}
       (:display-name user)
       ;; the content language being authored (the selected form language), not the interface
       (or form-lang lang)]
      [cite/citation-editor
       text
       #(rf/dispatch [::new-set :text %])
       (lbl lang labels :text-ph)
       nil
       (dk/kind-allows-inputs? (or kind "inference"))
       #(rf/dispatch [::new-set :cites (source/add-cite cites %)])]
      [source/cites-list cites #(rf/dispatch [::new-set :cites %])]
      (when (= kind "source")
        [:div {:style {:margin "0.9em 0 0.2em"}}
         [source/source-editor source #(rf/dispatch [::new-set :source %])]])
      [:div {:style {:display "flex"
                     :gap "0.5em"
                     :margin-top "0.9em"}}
       [:button {:on-click (cond
                             (not user) #(rf/dispatch [::auth/open :login])
                             (or blank? submitting?) nil
                             :else #(rf/dispatch [::new-submit]))
                 :disabled (boolean (and user (or blank? submitting?)))
                 :style {:padding "0.4em 0.9em"
                         :border "none"
                         :background "#b9770e"
                         :color "#fff"
                         :border-radius "0.3em"
                         :opacity (if user 1 0.7)
                         :cursor (if (and user (or blank? submitting?)) "default" "pointer")}}
        (cond
          (not user) (lbl lang labels :login)
          submitting? (lbl lang labels :creating)
          :else (lbl lang labels :create))]
       [:a {:href cancel-url
            :style {:padding "0.4em 0.9em"
                    :border "1px solid #ccc"
                    :background "#fff"
                    :border-radius "0.3em"
                    :text-decoration "none"
                    :color "#444"}}
        (lbl lang labels :cancel)]]])))
