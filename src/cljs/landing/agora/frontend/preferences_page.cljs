(ns landing.agora.frontend.preferences-page
  "The preferences page (`/agora/<lang>/preferences`): the interface-language chooser and
  the account section."
  (:require
   [landing.agora.frontend.auth          :as auth]
   [landing.agora.frontend.document-page :as    dv
                                         :refer [card-style language-selector]]
   [landing.agora.frontend.i18n          :as i18n]
   [landing.agora.frontend.ui-commons    :as ui]
   [re-frame.core                        :as rf]))

(defn- provider-label
  "Human name for how the account signs in."
  [lang provider]
  (case provider
    "google" "Google"
    (i18n/t lang :prefs/via-password)))

(defn- pref-field
  "A label / value row for the account section."
  [label value]
  [:div {:style {:display "flex"
                 :justify-content "space-between"
                 :gap "1em"
                 :padding "0.35em 0"
                 :border-bottom "1px solid #f0eee8"}}
   [:span {:style {:color "#888"
                   :font-size "0.9em"}}
    label]
   [:span {:style {:font-weight 600
                   :text-align "right"
                   :word-break "break-word"}}
    value]])

(def ^:private alias-error-keys
  "The rename endpoint's error codes → their message."
  {"missing" :prefs/alias-missing
   "too-long" :prefs/alias-too-long
   "alias-taken" :prefs/alias-taken})

(def ^:private small-btn
  {:font-size "0.8em"
   :padding "0.3em 0.7em"
   :border "1px solid #ccc"
   :border-radius "0.3em"
   :background "#fff"
   :cursor "pointer"})

(defn- alias-editor
  "The open rename form: the field, the warning, and the outcome. The warning belongs here and
  nowhere else — typing a name is the only path that can tie a civil identity to one's positions,
  and it is never the path taken by default."
  [lang {:keys [alias error submitting?]}]
  [:div {:style {:padding "0.35em 0"
                 :border-bottom "1px solid #f0eee8"}}
   [:div {:style {:color "#888"
                  :font-size "0.9em"
                  :margin-bottom "0.3em"}}
    (i18n/t lang :auth/alias)]
   [ui/composed-field {:type "text"
                       :value (or alias "")
                       :on-text #(rf/dispatch [::auth/set-alias-text %])
                       :style {:width "100%"
                               :box-sizing "border-box"
                               :padding "0.5em"
                               :font-size "0.95em"
                               :border "1px solid #ccc"
                               :border-radius "0.3em"}}]
   [:div {:style {:font-size "0.8em"
                  :color "#8a6d3b"
                  :margin "0.4em 0"}}
    (i18n/t lang :prefs/alias-warning)]
   (when error
     [:div {:style {:font-size "0.85em"
                    :color "#c92a2a"
                    :margin-bottom "0.4em"}}
      (i18n/t lang (get alias-error-keys error :prefs/alias-failed))])
   [:div {:style {:display "flex"
                  :gap "0.5em"}}
    [:button {:on-click #(when-not submitting? (rf/dispatch [::auth/submit-alias]))
              :disabled (boolean submitting?)
              :style
              (assoc small-btn :border "1px solid #b9770e" :background "#b9770e" :color "#fff")}
     (if submitting? "…" (i18n/t lang :prefs/alias-save))]
    [:button {:on-click #(rf/dispatch [::auth/close-alias-form])
              :style small-btn}
     (i18n/t lang :form/cancel)]]])

(defn- alias-row
  "The account's public name: the value with a rename button, or the editor once open."
  [lang user]
  (if-let [form @(rf/subscribe [::auth/alias-form])]
    [alias-editor lang form]
    [pref-field
     (i18n/t lang :auth/alias)
     [:span {:style {:display "inline-flex"
                     :align-items "center"
                     :gap "0.6em"}}
      (:display-name user)
      [:button {:on-click #(rf/dispatch [::auth/edit-alias])
                :title (i18n/t lang :prefs/rename-alias)
                :style (assoc small-btn :font-weight 400)}
       (i18n/t lang :prefs/rename-alias)]]]))

(defn preferences-page
  "User preferences: account details — the alias, renameable here, plus the login and sign-in method
  — and the interface language. A home for further settings later. Works for anyone — the language is
  cached locally and, when logged in, persisted to the account."
  []
  (let [lang @(rf/subscribe [::i18n/lang])
        user @(rf/subscribe [::auth/user])
        section-title {:font-size "1.05em"
                       :margin "1.2em 0 0.5em"
                       :color "#2a2723"}]
    [:div {:style (assoc card-style :margin "1.5em auto")}
     [:h1 {:style {:font-size "1.3em"
                   :margin "0 0 0.3em"}}
      (i18n/t lang :prefs/title)]
     ;; ---- Account ----
     [:h2 {:style section-title}
      (i18n/t lang :prefs/account)]
     (if user
       [:div {:style {:display "flex"
                      :align-items "center"
                      :gap "0.8em"}}
        (when-let [avatar (:avatar-url user)]
          [:img {:src avatar
                 :alt (:display-name user)
                 :referrer-policy "no-referrer"
                 :style {:width "3em"
                         :height "3em"
                         :border-radius "50%"
                         :object-fit "cover"
                         :border "1px solid #d99a2b"}}])
        [:div {:style {:flex "1 1 auto"}}
         [alias-row lang user]
         [pref-field (i18n/t lang :auth/email) (:email user)]
         [pref-field (i18n/t lang :prefs/connection) (provider-label lang (:provider user))]]]
       [:div {:style {:color "#888"
                      :font-style "italic"}}
        (i18n/t lang :prefs/not-signed-in)])
     ;; ---- Language ----
     [:h2 {:style section-title}
      (i18n/t lang :form/language)]
     [language-selector lang #(rf/dispatch [:agora/set-lang %])]]))
