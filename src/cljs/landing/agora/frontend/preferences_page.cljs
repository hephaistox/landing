(ns landing.agora.frontend.preferences-page
  "The preferences page (`/agora/<lang>/preferences`): the interface-language chooser and
  the account section."
  (:require
   [landing.agora.frontend.auth          :as auth]
   [landing.agora.frontend.document-page :as    dv
                                         :refer [card-style language-selector]]
   [landing.agora.frontend.i18n          :as i18n]
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

(defn preferences-page
  "User preferences: account details (alias, login, sign-in method) and the
  interface language. A home for further settings later. Works for anyone — the
  language is cached locally and, when logged in, persisted to the account."
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
         [pref-field (i18n/t lang :auth/alias) (:display-name user)]
         [pref-field (i18n/t lang :auth/email) (:email user)]
         [pref-field (i18n/t lang :prefs/connection) (provider-label lang (:provider user))]]]
       [:div {:style {:color "#888"
                      :font-style "italic"}}
        (i18n/t lang :prefs/not-signed-in)])
     ;; ---- Language ----
     [:h2 {:style section-title}
      (i18n/t lang :form/language)]
     [language-selector lang #(rf/dispatch [:agora/set-lang %])]]))
