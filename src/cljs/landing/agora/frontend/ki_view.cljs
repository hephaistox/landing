(ns landing.agora.frontend.ki-view
  "The KI page — a thin layer over the shared document engine (`document-view`): the
  read/edit KI card (kind selector, in-place edit, versioned identity), the standalone
  KI creation form, and their re-frame edit/create events. Everything generic — the
  graph layout, badges, discover cards, the translate flow, the app chrome — lives in
  `document-view`."
  (:require
   [clojure.string                       :as str]
   [landing.agora.document-domain        :as domain]
   [landing.agora.frontend.auth          :as auth]
   [landing.agora.frontend.cite          :as cite]
   [landing.agora.frontend.document-view :as dv
    :refer [byline card-style display-title gated input-drop-fn json-req kind-badge
            lang-badge language-selector languages-control node-frame permalink
            version-picker]]
   [landing.agora.frontend.i18n          :as i18n]
   [landing.agora.frontend.ui-commons    :as ui]
   [landing.language                     :as language]
   [re-frame.core                        :as rf]
   [superstructor.re-frame.fetch-fx]))

(defn type-selector
  "All KI types as clickable badges; the selected one is highlighted, the others
  dimmed. Calls `on-select` with the chosen type string."
  [selected on-select]
  (into [:div {:style {:display "flex"
                       :flex-wrap "wrap"
                       :gap "0.4em"}}]
        ;; `domain/kind-ids` is the canonical set as keywords; the DB/API represent
        ;; the kind as a string, so map to `(name kw)` at this boundary.
        (for [t (map name domain/kind-ids)
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
;; State + operations (edit + links)
;; ===========================================================================

(rf/reg-sub ::edit (fn [db _] (::edit db)))

(defn close-panels
  "Collapse the edit form. Used by core on route changes so nothing lingers from a
  previous KI."
  [db]
  (update db ::edit assoc :open? false))

(rf/reg-event-db ::op-failed
                 (fn [db [_ resp]]
                   (js/console.error "[agora] operation failed:" (clj->js resp))
                   (update db ::edit assoc :saving? false :error resp)))

;; ---- Edit (new minor version) ----

(rf/reg-event-db ::edit-open
                 (fn [db [_ ki]]
                   (assoc db
                          ::edit
                          {:open? true
                           :title (:title ki)
                           :kind (:kind ki)
                           :statement (cite/node-text ki)
                           ;; citations present when editing began — to warn if an
                           ;; input reference gets removed before saving.
                           :orig-cites (cite/citations (cite/node-text ki))
                           :saving? false
                           :error nil})))

(rf/reg-event-db ::edit-close (fn [db _] (update db ::edit assoc :open? false)))
(rf/reg-event-db ::edit-set (fn [db [_ k v]] (update db ::edit assoc k v)))

(rf/reg-event-fx ::edit-save
                 (fn [{:keys [db]} [_ ki-id]]
                   (let [{:keys [title kind statement orig-cites]} (::edit db)
                         removed? (seq (remove (cite/citations statement) orig-cites))]
                     (if (and removed?
                              (not (js/confirm (i18n/t (i18n/current db) :cite/removed-warning))))
                       {}
                       {:db (update db ::edit assoc :saving? true :error nil)
                        :fetch (json-req :post
                                         (str "/agora/api/ki/" ki-id "/edit")
                                         {:title title
                                          :kind kind
                                          :text statement}
                                         [::edit-save-ok]
                                         [::op-failed])}))))

(rf/reg-event-fx ::edit-save-ok
                 (fn [{:keys [db]} [_ resp]]
                   (let [ki (:body resp)]
                     (if (:id ki)
                       {:dispatch [:agora/edited ki]}
                       {:db (update db ::edit assoc :saving? false :error resp)}))))

;; ---- Translate: duplicate a KI (and its inputs) into another language ----
;;
;; Opening the editor fetches a machine-translation suggestion; the author edits
;; it against the read-only source, then saves — which creates the new-language
;; KI (with the validated text) and its inputs, and lands on it.

(rf/reg-sub ::new (fn [db _] (::new db)))
(rf/reg-event-db ::new-set (fn [db [_ k v]] (assoc-in db [::new k] v)))

(rf/reg-event-fx ::new-submit
                 (fn [{:keys [db]} _]
                   (let [{:keys [title kind lang statement]} (::new db)]
                     {:db (assoc-in db [::new :submitting?] true)
                      :fetch (json-req :post
                                       "/agora/api/ki"
                                       {:title title
                                        :kind (or kind "inference")
                                        :lang (or lang (i18n/current db))
                                        :text statement}
                                       [::new-created]
                                       [::op-failed])})))

(rf/reg-event-fx ::new-created
                 (fn [{:keys [db]} [_ resp]]
                   ;; :agora/edited caches the new KI and navigates to its page (where inputs can
                   ;; then be added). Clear the form.
                   {:db (dissoc db ::new)
                    :dispatch [:agora/edited (:body resp)]}))

;; ===========================================================================
;; Components
;; ===========================================================================

(defn- edit-card
  "The card in edit mode: type selector, editable title + statement, in place."
  [{ki-name :name
    ki-lang :lang
    :keys [id major minor published-at author]}
   {:keys [title kind statement saving? error]}]
  (let [lang @(rf/subscribe [::i18n/lang])]
    [:article {:style card-style}
     [:div {:style {:display "flex"
                    :align-items "center"
                    :gap "0.75em"
                    :margin-bottom "0.6em"}}
      [type-selector kind #(rf/dispatch [::edit-set :kind %])]
      [lang-badge ki-lang]
      [:span {:style {:color "#888"
                      :font-size "0.8em"
                      :font-family "monospace"}}
       (str "v" major "." minor " " (i18n/t lang :form/next))]]
     [:input {:type "text"
              :value (or title "")
              :placeholder (display-title nil ki-name)
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
     [cite/citation-editor
      statement
      #(rf/dispatch [::edit-set :statement %])
      (i18n/t lang :form/statement-ph)
      ki-name]
     (when error
       [:div {:style {:color "#c92a2a"
                      :font-size "0.85em"
                      :margin-top "0.5em"}}
        (i18n/t lang :form/save-failed)])
     [:div {:style {:display "flex"
                    :gap "0.5em"
                    :margin-top "0.7em"}}
      [:button {:on-click #(rf/dispatch [::edit-save id])
                :disabled (boolean saving?)
                :style {:padding "0.4em 0.9em"
                        :border "none"
                        :background "#b9770e"
                        :color "#fff"
                        :border-radius "0.3em"
                        :cursor (if saving? "default" "pointer")}}
       (if saving? (i18n/t lang :form/saving) (i18n/t lang :form/save))]
      [:button {:on-click #(rf/dispatch [::edit-close])
                :style {:padding "0.4em 0.9em"
                        :border "1px solid #ccc"
                        :background "#fff"
                        :border-radius "0.3em"
                        :cursor "pointer"}}
       (i18n/t lang :form/cancel)]]]))

(defn- static-card
  "The card in read mode. When editable (`edit?` true) a pencil switches to in-place
  editing; on the public page it is omitted."
  [{ki-name :name
    ki-title :title
    ki-type :kind
    :keys [major minor published-at versions author author-id]
    :as ki}
   edit?]
  (let [lang @(rf/subscribe [::i18n/lang])]
    [:article {:style card-style}
     (when edit?
       (let [[on-click dim?] (gated #(rf/dispatch [::edit-open ki]))]
         [:button {:on-click on-click
                   :title (if dim? (i18n/t lang :ki/login-to-edit) (i18n/t lang :ki/edit))
                   :style {:position "absolute"
                           :top "0.7em"
                           :right "0.8em"
                           :border "1px solid #ddd"
                           :background "#fff"
                           :color "#b9770e"
                           :border-radius "0.3em"
                           :width "2em"
                           :height "2em"
                           :cursor "pointer"
                           :font-size "0.95em"
                           :line-height 1
                           :opacity (if dim? 0.4 1)}}
          "✎"]))
     [:div {:style {:display "flex"
                    :align-items "center"
                    :gap "0.75em"
                    :margin-bottom "0.5em"}}
      [kind-badge ki-type {:link? true}]
      [languages-control lang ki]
      [version-picker {:major major
                       :minor minor
                       :versions versions}
       (fn [id] (i18n/ki-id lang id))]]
     [:h1 {:style {:font-size "1.3em"
                   :margin "0.2em 0 0.1em"}}
      (display-title ki-title ki-name)]
     [byline author published-at author-id]
     [:div {:style {:font-size "1.05em"
                    :line-height "1.5"
                    :color "#222"}}
      [cite/render-text (cite/node-text ki)]]]))

(defn- ki-card
  [ki]
  (let [edit @(rf/subscribe [::edit])]
    (if (:open? edit) [edit-card ki edit] [static-card ki true])))

(defn creation-form
  "Standalone form to create a new KI (#34). On save it POSTs /agora/api/ki and
  navigates to the new KI's page, where inputs can then be linked."
  []
  (let [{:keys [title kind statement submitting?]
         form-lang :lang}
        @(rf/subscribe [::new])
        user @(rf/subscribe [::auth/user])
        lang @(rf/subscribe [::i18n/lang])
        label-style {:font-size "0.8em"
                     :color "#555"
                     :margin-bottom "0.3em"}
        ;; the identity slug is derived from the title server-side, so it isn't asked
        blank? (or (str/blank? title) (str/blank? statement))]
    [:div {:style (assoc card-style :margin "1.5em auto")}
     [ui/on-escape #(rf/dispatch [:agora/cancel-new])]
     [:h1 {:style {:font-size "1.3em"
                   :margin "0 0 0.8em"}}
      (i18n/t lang :form/new-title)]
     [:div {:style label-style}
      (i18n/t lang :form/title)]
     [:input {:type "text"
              :placeholder (i18n/t lang :form/title-ph)
              :value (or title "")
              :on-change #(rf/dispatch [::new-set :title (.. % -target -value)])
              :style {:width "100%"
                      :box-sizing "border-box"
                      :padding "0.5em"
                      :font-family "inherit"
                      :font-size "0.95em"
                      :border "1px solid #ccc"
                      :border-radius "0.3em"
                      :margin-bottom "0.8em"}}]
     [:div {:style label-style}
      (i18n/t lang :form/type)]
     [:div {:style {:margin-bottom "0.8em"}}
      [type-selector (or kind "inference") #(rf/dispatch [::new-set :kind %])]]
     [:div {:style label-style}
      (i18n/t lang :form/language)]
     [:div {:style {:margin-bottom "0.8em"}}
      [language-selector (or form-lang lang) #(rf/dispatch [::new-set :lang %])]]
     [:div {:style label-style}
      (i18n/t lang :form/statement)]
     [cite/citation-editor
      statement
      #(rf/dispatch [::new-set :statement %])
      (i18n/t lang :form/statement-ph)]
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
         (not user) (i18n/t lang :form/login-to-create)
         submitting? (i18n/t lang :form/creating)
         :else (i18n/t lang :form/create))]
      [:a {:href (i18n/discover lang)
           :style {:padding "0.4em 0.9em"
                   :border "1px solid #ccc"
                   :background "#fff"
                   :border-radius "0.3em"
                   :text-decoration "none"
                   :color "#444"}}
       (i18n/t lang :form/cancel)]]]))

(defn ki-page
  "The editable KI page — the shared node layout with an editable central card. Inputs
  carry a ✕ (for logged-in viewers) that removes the input link from the input field."
  [ki]
  (let [lang @(rf/subscribe [::i18n/lang])]
    [node-frame
     ki
     [ki-card ki]
     (fn [doc] (i18n/ki-id lang (:id doc)))
     (input-drop-fn "ki" (:id ki))]))

(defn public-ki-page
  "Read-only public KI page (#35): the same layout, a static central card, neighbour
  links pointing at public permalinks."
  [ki]
  (let [lang @(rf/subscribe [::i18n/lang])]
    [node-frame ki [static-card ki false] (fn [doc] (permalink lang doc))]))

;; ===========================================================================
;; Discoverability page (#36)
;; ===========================================================================

