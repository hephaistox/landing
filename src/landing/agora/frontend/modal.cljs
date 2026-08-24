(ns landing.agora.frontend.modal
  "Shared React modal dialogs for the Agora SPA — a coherent replacement for the browser's own
  `js/confirm`/`alert`, which block the page and can't be styled or intercepted. Today: a re-frame
  driven confirmation dialog, mounted once at the app root (`confirm-modal`). Any code opens it with
  `(rf/dispatch [::confirm {…}])` and reacts through the `:on-confirm`/`:on-cancel` events it carries,
  so a confirmation is asynchronous like any other dialog rather than an inline boolean."
  (:require
   [landing.agora.frontend.i18n       :as i18n]
   [landing.agora.frontend.ui-commons :as ui]
   [re-frame.core                     :as rf]))

(rf/reg-sub ::request (fn [db _] (:agora/confirm db)))

;; open the dialog with a request map (see `confirm-modal`)
(rf/reg-event-db ::confirm (fn [db [_ request]] (assoc db :agora/confirm request)))

;; close it and dispatch the decision's event (`:on-confirm` for :yes, `:on-cancel` for :no), if any
(rf/reg-event-fx ::resolve
                 (fn [{:keys [db]} [_ decision]]
                   (let [{:keys [on-confirm on-cancel]} (:agora/confirm db)
                         ev (case decision
                              :yes on-confirm
                              :no on-cancel)]
                     (cond-> {:db (dissoc db :agora/confirm)}
                       ev (assoc :dispatch ev)))))

(defn confirm-modal
  "The shared confirmation dialog, rendered once at the app root. Opened with
  `(rf/dispatch [::confirm {:message … :confirm-label … :cancel-label … :danger? … :on-confirm [ev…]
  :on-cancel [ev…]}])`; every field but `:message` is optional. Confirm dispatches `:on-confirm`; Esc,
  the backdrop and Cancel all take the cancel path (`:on-cancel`). Labels default to a generic
  Confirm/Cancel; `:danger?` tints the confirm button red for a destructive action."
  []
  (when-let [{:keys [message confirm-label cancel-label danger?]} @(rf/subscribe [::request])]
    (let [lang @(rf/subscribe [::i18n/lang])
          accent (if danger? "#c92a2a" "#1d6b2f")]
      [:div {:on-click #(rf/dispatch [::resolve :no])
             :style {:position "fixed"
                     :inset 0
                     :z-index 200
                     :background "rgba(0,0,0,0.45)"
                     :display "flex"
                     :align-items "flex-start"
                     :justify-content "center"
                     :padding-top "12vh"}}
       [ui/on-escape #(rf/dispatch [::resolve :no])]
       [:div {:on-click #(.stopPropagation %)
              :style {:width "24em"
                      :max-width "90%"
                      :background "#fff"
                      :border-radius "0.6em"
                      :padding "1.4em"
                      :font-family "system-ui, sans-serif"
                      :box-shadow "0 10px 30px rgba(0,0,0,0.25)"}}
        [:div {:style {:font-size "0.98em"
                       :line-height 1.5
                       :color "#222"
                       :margin-bottom "1.2em"}}
         message]
        [:div {:style {:display "flex"
                       :justify-content "flex-end"
                       :gap "0.6em"}}
         [:button {:on-click #(rf/dispatch [::resolve :no])
                   :style {:padding "0.45em 1em"
                           :border "1px solid #ccc"
                           :background "#fff"
                           :color "#555"
                           :border-radius "0.35em"
                           :cursor "pointer"
                           :font-size "0.9em"}}
          (or cancel-label (i18n/t lang :modal/cancel))]
         [:button {:on-click #(rf/dispatch [::resolve :yes])
                   :style {:padding "0.45em 1.1em"
                           :border "none"
                           :background accent
                           :color "#fff"
                           :border-radius "0.35em"
                           :cursor "pointer"
                           :font-weight 700
                           :font-size "0.9em"}}
          (or confirm-label (i18n/t lang :modal/confirm))]]]])))
