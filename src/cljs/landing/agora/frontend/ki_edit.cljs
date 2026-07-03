(ns landing.agora.frontend.ki-edit
  "Edit affordance for a KI (#32). A collapsed \"Edit\" button expands a form
  pre-filled with the KI's current type and statement. Saving POSTs to
  /api/ki/:id/edit, which creates a new minor version (never in-place), then the
  browser navigates to that new minor's page.

  Not auth-gated yet — OAuth arrives in #38."
  (:require
   [landing.agora.frontend.ki-edit-common :refer [ki-types]]
   [re-frame.core                         :as rf]
   [superstructor.re-frame.fetch-fx]))

;; ---------------------------------------------------------------------------
;; Form state (under ::form in app-db)
;; ---------------------------------------------------------------------------

(rf/reg-sub ::form (fn [db _] (::form db)))

(rf/reg-event-db ::open
                 (fn [db [_ ki]]
                   (assoc db
                          ::form
                          {:open? true
                           :type (:type ki)
                           :output-statement (:output-statement ki)
                           :saving? false
                           :error nil})))

(rf/reg-event-db ::close (fn [db _] (update db ::form assoc :open? false)))
(rf/reg-event-db ::set-field (fn [db [_ k v]] (update db ::form assoc k v)))

(defn close-form
  "Collapse the edit form. Used on route changes so the form never lingers open
  with a previous KI's content."
  [db]
  (update db ::form assoc :open? false))

(rf/reg-event-fx ::save
                 (fn [{:keys [db]} [_ ki-id]]
                   (let [{:keys [type output-statement]} (::form db)]
                     {:db (update db ::form assoc :saving? true :error nil)
                      :fetch {:method :post
                              :url (str "/api/ki/" ki-id "/edit")
                              :headers {"Content-Type" "application/json"
                                        "Accept" "application/json"}
                              :body (js/JSON.stringify (clj->js {:type type
                                                                 :output-statement
                                                                 output-statement}))
                              :response-content-types {#"application/json" :json}
                              :on-success [::save-ok]
                              :on-failure [::save-failed]}})))

(rf/reg-event-fx ::save-ok
                 (fn [{:keys [db]} [_ response]]
                   ;; fetch-fx already parsed the JSON body into a keywordized CLJS map, so the
                   ;; body is the new KI version. Hand it to :agora/edited, which caches it and
                   ;; navigates locally (no refetch; neighbours resolve to it via the latest
                   ;; index).
                   (let [ki (:body response)]
                     (if (:id ki)
                       {:dispatch [:agora/edited ki]}
                       (do (js/console.error "[agora] edit succeeded but no id in response:"
                                             (clj->js response))
                           {:db (update db ::form assoc :saving? false :error response)})))))

(rf/reg-event-db ::save-failed
                 (fn [db [_ response]]
                   (js/console.error "[agora] KI edit failed:" (clj->js response))
                   (update db ::form assoc :saving? false :error response)))

;; ---------------------------------------------------------------------------
;; View
;; ---------------------------------------------------------------------------

(defn- input-style
  []
  {:width "100%"
   :box-sizing "border-box"
   :padding "0.5em"
   :font-family "inherit"
   :font-size "0.95em"
   :border "1px solid #ccc"
   :border-radius "0.3em"})

(defn edit-panel
  "Edit control for `ki`: a button that toggles into an inline edit form."
  [ki]
  (let [{:keys [open? type output-statement saving? error]} @(rf/subscribe [::form])]
    [:div {:style {:width "40em"
                   :max-width "100%"
                   :margin "0.8em auto 0"
                   :font-family "system-ui, sans-serif"}}
     (if-not open?
       [:button {:on-click #(rf/dispatch [::open ki])
                 :style {:padding "0.4em 0.9em"
                         :border "1px solid #b9770e"
                         :background "#fff"
                         :color "#b9770e"
                         :border-radius "0.3em"
                         :cursor "pointer"}}
        "Edit"]
       [:div {:style {:border "1px solid #ddd"
                      :border-radius "0.5em"
                      :padding "1em"
                      :background "#fafafa"}}
        [:div {:style {:font-size "0.72em"
                       :text-transform "uppercase"
                       :letter-spacing "0.06em"
                       :color "#888"
                       :margin-bottom "0.6em"}}
         "New version of "
         (:name ki)
         " (v"
         (:major ki)
         ".x)"]
        [:label {:style {:display "block"
                         :margin-bottom "0.7em"}}
         [:div {:style {:font-size "0.8em"
                        :color "#555"
                        :margin-bottom "0.2em"}}
          "Type"]
         [:select {:value type
                   :on-change #(rf/dispatch [::set-field :type (.. % -target -value)])
                   :style (input-style)}
          (for [t ki-types]
            ^{:key t}
            [:option {:value t}
             t])]]
        [:label {:style {:display "block"
                         :margin-bottom "0.8em"}}
         [:div {:style {:font-size "0.8em"
                        :color "#555"
                        :margin-bottom "0.2em"}}
          "Output statement"]
         [:textarea {:value output-statement
                     :rows 4
                     :on-change #(rf/dispatch [::set-field :output-statement (.. % -target -value)])
                     :style (input-style)}]]
        (when error
          [:div {:style {:color "#c92a2a"
                         :font-size "0.85em"
                         :margin-bottom "0.6em"}}
           "Save failed — see console."])
        [:div {:style {:display "flex"
                       :gap "0.5em"}}
         [:button {:on-click #(rf/dispatch [::save (:id ki)])
                   :disabled (boolean saving?)
                   :style {:padding "0.4em 0.9em"
                           :border "none"
                           :background "#b9770e"
                           :color "#fff"
                           :border-radius "0.3em"
                           :cursor (if saving? "default" "pointer")}}
          (if saving? "Saving…" "Save new version")]
         [:button {:on-click #(rf/dispatch [::close])
                   :style {:padding "0.4em 0.9em"
                           :border "1px solid #ccc"
                           :background "#fff"
                           :border-radius "0.3em"
                           :cursor "pointer"}}
          "Cancel"]]])]))
