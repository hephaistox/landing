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
   [landing.agora.document-domain        :as domain]
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

(defn type-selector
  "Every kind of `object-type` (KI kinds vs article kinds are disjoint sets) as a clickable
  badge; the selected one highlighted, the others dimmed. Calls `on-select` with the chosen
  kind string."
  [object-type selected on-select]
  (into [:div {:style {:display "flex"
                       :flex-wrap "wrap"
                       :gap "0.4em"}}]
        ;; `domain/kind-ids-of` is the canonical per-type set as keywords; the DB/API represent
        ;; the kind as a string, so map to `(name kw)` at this boundary.
        (for [t (map name (domain/kind-ids-of object-type))
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
  "Collapse the edit form. Used by core on route changes (and after a save) so nothing
  lingers from a previous document."
  [db]
  (update db ::edit assoc :open? false))

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
                           ;; the quoted source-KIs (edge-only inputs) — resubmitted on save
                           :quotes (vec (:quotes doc))
                           ;; citations present when editing began — to warn if an input
                           ;; reference gets removed before saving.
                           :orig-cites (cite/citations (cite/node-text doc))
                           :saving? false
                           :error nil})))

(rf/reg-event-db ::edit-close (fn [db _] (update db ::edit assoc :open? false)))
(rf/reg-event-db ::edit-set (fn [db [_ k v]] (update db ::edit assoc k v)))

(rf/reg-event-fx ::edit-save
                 (fn [{:keys [db]} _]
                   (let [{:keys [type id title kind text source quotes orig-cites]} (::edit db)
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
                                                  :quotes (source/strip-quotes quotes)}
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
                           :kind (name (first (domain/kind-ids-of type)))})))
(rf/reg-event-db ::new-set (fn [db [_ k v]] (assoc-in db [::new k] v)))

(rf/reg-event-fx ::new-submit
                 (fn [{:keys [db]} _]
                   (let [{:keys [type show-kind? title kind lang text source quotes]} (::new db)]
                     {:db (assoc-in db [::new :submitting?] true)
                      :fetch (json-req :post
                                       (str "/agora/api/" type)
                                       (cond-> {:title title
                                                :lang (or lang (i18n/current db))
                                                :text text
                                                :source (source/strip-source source)
                                                :quotes (source/strip-quotes quotes)}
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
                       {:db (dissoc db ::new)
                        :dispatch [:agora/saved doc]}
                       {:db (update db ::edit assoc :saving? false :error resp)}))))

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
  [{:keys [kind title source quotes]} author-name lang]
  (when-let [p (domain/statement-prefix-of {:kind kind
                                            :title title
                                            :source source
                                            :quote-author-name (:author-name (first quotes))
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
  (let [{:keys [title kind text source quotes saving? error]} @(rf/subscribe [::edit])
        lang @(rf/subscribe [::i18n/lang])
        user @(rf/subscribe [::auth/user])]
    [:article {:style card-style}
     [:div {:style {:display "flex"
                    :align-items "center"
                    :gap "0.75em"
                    :margin-bottom "0.6em"}}
      (when show-kind? [type-selector object-type kind #(rf/dispatch [::edit-set :kind %])])
      [lang-badge doc-lang]
      [:span {:style {:color "#888"
                      :font-size "0.8em"
                      :font-family "monospace"}}
       (str "v" major "." minor " " (i18n/t lang :form/next))]]
     [:input {:type "text"
              :value (or title "")
              :on-change #(rf/dispatch [::edit-set :title (.. % -target -value)])
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
                    :quotes quotes}
      (:display-name user)
      ;; the doc's content language (not the interface lang) — the prefix is part of the text
      doc-lang]
     [cite/citation-editor
      text
      #(rf/dispatch [::edit-set :text %])
      (lbl lang labels :text-ph)
      doc-name
      (domain/kind-allows-inputs? kind)
      #(rf/dispatch [::edit-set :quotes (source/add-quote quotes %)])]
     [source/quotes-list quotes #(rf/dispatch [::edit-set :quotes %])]
     ;; only a source-KI carries a `:source` (a reference to its shared work) — offer the
     ;; source picker just for that kind; other KIs relate to sources by *quoting* them (above)
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
   (let [{:keys [title kind text source quotes submitting?]
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
      [:input {:type "text"
               :placeholder (lbl lang labels :title-ph)
               :value (or title "")
               :on-change #(rf/dispatch [::new-set :title (.. % -target -value)])
               :style title-input-style}]
      (when show-kind?
        [:<>
         [:div {:style label-style}
          (i18n/t lang :form/type)]
         [:div {:style {:margin-bottom "0.8em"}}
          [type-selector object-type kind #(rf/dispatch [::new-set :kind %])]]])
      [:div {:style label-style}
       (i18n/t lang :form/language)]
      [:div {:style {:margin-bottom "0.8em"}}
       [language-selector (or form-lang lang) #(rf/dispatch [::new-set :lang %])]]
      [:div {:style label-style}
       (lbl lang labels :text)]
      [prefix-label {:kind kind
                     :title title
                     :source source
                     :quotes quotes}
       (:display-name user)
       ;; the content language being authored (the selected form language), not the interface
       (or form-lang lang)]
      [cite/citation-editor
       text
       #(rf/dispatch [::new-set :text %])
       (lbl lang labels :text-ph)
       nil
       (domain/kind-allows-inputs? (or kind "inference"))
       #(rf/dispatch [::new-set :quotes (source/add-quote quotes %)])]
      [source/quotes-list quotes #(rf/dispatch [::new-set :quotes %])]
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
