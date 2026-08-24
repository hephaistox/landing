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

(def ^:private display-name-error-keys
  "The rename endpoint's error codes → their message."
  {"missing" :prefs/display-name-missing
   "too-long" :prefs/display-name-too-long
   "display-name-taken" :prefs/display-name-taken})

(def ^:private small-btn
  {:font-size "0.8em"
   :padding "0.3em 0.7em"
   :border "1px solid #ccc"
   :border-radius "0.3em"
   :background "#fff"
   :cursor "pointer"})

(defn- display-name-editor
  "The open rename form: the field, the warning, and the outcome. The warning belongs here and
  nowhere else — typing a name is the only path that can tie a civil identity to one's positions,
  and it is never the path taken by default."
  [lang {:keys [display-name error submitting?]}]
  [:div {:style {:padding "0.35em 0"
                 :border-bottom "1px solid #f0eee8"}}
   [:div {:style {:color "#888"
                  :font-size "0.9em"
                  :margin-bottom "0.3em"}}
    (i18n/t lang :auth/display-name)]
   [ui/composed-field {:type "text"
                       :value (or display-name "")
                       :on-text #(rf/dispatch [::auth/set-display-name-text %])
                       :style {:width "100%"
                               :box-sizing "border-box"
                               :padding "0.5em"
                               :font-size "0.95em"
                               :border "1px solid #ccc"
                               :border-radius "0.3em"}}]
   [:div {:style {:font-size "0.8em"
                  :color "#8a6d3b"
                  :margin "0.4em 0"}}
    (i18n/t lang :prefs/display-name-warning)]
   (when error
     [:div {:style {:font-size "0.85em"
                    :color "#c92a2a"
                    :margin-bottom "0.4em"}}
      (i18n/t lang (get display-name-error-keys error :prefs/display-name-failed))])
   [:div {:style {:display "flex"
                  :gap "0.5em"}}
    [:button {:on-click #(when-not submitting? (rf/dispatch [::auth/submit-display-name]))
              :disabled (boolean submitting?)
              :style
              (assoc small-btn :border "1px solid #b9770e" :background "#b9770e" :color "#fff")}
     (if submitting? "…" (i18n/t lang :prefs/display-name-save))]
    [:button {:on-click #(rf/dispatch [::auth/close-display-name-form])
              :style small-btn}
     (i18n/t lang :form/cancel)]]])

(defn- display-name-row
  "The account's public name: the value with a rename button, or the editor once open."
  [lang user]
  (if-let [form @(rf/subscribe [::auth/display-name-form])]
    [display-name-editor lang form]
    [pref-field
     (i18n/t lang :auth/display-name)
     [:span {:style {:display "inline-flex"
                     :align-items "center"
                     :gap "0.6em"}}
      (:display-name user)
      ;; a pencil, labelled by its tooltip — the same gesture, and the same glyph, as renaming a
      ;; publication. The name is what the row is about; the button beside it should not compete
      ;; with it for the eye.
      [dv/tooltip
       (i18n/t lang :prefs/rename-display-name)
       [:button {:on-click #(rf/dispatch [::auth/edit-display-name])
                 :style {:border "none"
                         :background "transparent"
                         :cursor "pointer"
                         :font-size "1em"
                         :color "#b98a3e"
                         :padding 0
                         :line-height 1}}
        "✎"]]]]))

(def ^:private erase-error-keys
  "The erasure endpoint's error codes → their message."
  {"confirm-mismatch" :prefs/erase-mismatch})

(defn- erase-form
  "The open erasure form: what it destroys, and the display name to type back. Naming the consequences on the
  page rather than behind a modal is the point — one of the two outcomes (the published documents
  staying, de-linked) surprises people, and the other (the open publications going) cannot be undone."
  [lang user {:keys [confirm error submitting?]}]
  (let [matches? (= confirm (:display-name user))]
    [:div
     [:label {:style {:display "block"
                      :font-size "0.85em"
                      :margin-bottom "0.3em"}}
      (i18n/t lang :prefs/erase-confirm-label)]
     [ui/composed-field {:type "text"
                         :value (or confirm "")
                         :on-text #(rf/dispatch [::auth/set-erase-text %])
                         :style {:width "100%"
                                 :box-sizing "border-box"
                                 :padding "0.5em"
                                 :font-size "0.95em"
                                 :border "1px solid #ccc"
                                 :border-radius "0.3em"}}]
     (when error
       [:div {:style {:font-size "0.85em"
                      :color "#c92a2a"
                      :margin-top "0.4em"}}
        (i18n/t lang (get erase-error-keys error :prefs/erase-failed))])
     [:div {:style {:display "flex"
                    :gap "0.5em"
                    :margin-top "0.6em"}}
      [:button {:on-click #(when (and matches? (not submitting?))
                             (rf/dispatch [::auth/submit-erase]))
                :disabled (or (not matches?) (boolean submitting?))
                :style (assoc small-btn
                              :border "1px solid #c92a2a"
                              :background (if matches? "#c92a2a" "#e9b4b4")
                              :color "#fff"
                              :cursor (if matches? "pointer" "default"))}
       (if submitting? "…" (i18n/t lang :prefs/erase-submit))]
      [:button {:on-click #(rf/dispatch [::auth/close-erase-form])
                :style small-btn}
       (i18n/t lang :form/cancel)]]]))

(defn- erase-section
  "Account erasure, kept last and visually apart from the settings above it — it is not a preference.

  Closed, it is one button and nothing else: someone crossing this page to change their language
  should not have to read what erasing an account destroys. The warning panel opens with the form
  that acts on it, where it is what the reader came for rather than what they walked past."
  [lang user]
  (if-let [form @(rf/subscribe [::auth/erase-form])]
    [:div {:style {:margin-top "2em"
                   :padding "0.9em"
                   :border "1px solid #e6c9c9"
                   :border-radius "0.4em"
                   :background "#fdf7f7"}}
     [:h2 {:style {:font-size "1em"
                   :margin "0 0 0.4em"
                   :color "#a33"}}
      (i18n/t lang :prefs/erase-title)]
     [:div {:style {:font-size "0.85em"
                    :color "#5a4a4a"
                    :margin-bottom "0.5em"}}
      (i18n/t lang :prefs/erase-lead)]
     [:div {:style {:font-size "0.85em"
                    :color "#5a4a4a"
                    :margin-bottom "0.7em"}}
      (i18n/t lang :prefs/erase-irreversible)]
     [:div {:style {:font-size "0.85em"
                    :color "#5a4a4a"
                    :margin-bottom "0.7em"}}
      (i18n/t lang :prefs/erase-drafts)]
     [erase-form lang user form]]
    [:div {:style {:margin-top "2em"}}
     [:button {:on-click #(rf/dispatch [::auth/open-erase-form])
               :style (assoc small-btn :border "1px solid #c92a2a" :color "#c92a2a")}
      (i18n/t lang :prefs/erase-open)]]))

(defn preferences-page
  "User preferences: account details — the display name, renameable here, plus the login and sign-in method
  — the interface language, and erasing the account. A home for further settings later. Works for
  anyone — the language is cached locally and, when logged in, persisted to the account."
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
         [display-name-row lang user]
         [pref-field (i18n/t lang :auth/email) (:email user)]
         [pref-field (i18n/t lang :prefs/connection) (provider-label lang (:provider user))]]]
       [:div {:style {:color "#888"
                      :font-style "italic"}}
        (i18n/t lang :prefs/not-signed-in)])
     ;; ---- Language ----
     [:h2 {:style section-title}
      (i18n/t lang :form/language)]
     [language-selector lang #(rf/dispatch [:agora/set-lang %])]
     ;; ---- Erasure ----
     (when user [erase-section lang user])]))
