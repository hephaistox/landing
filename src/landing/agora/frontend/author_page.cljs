(ns landing.agora.frontend.author-page
  "The public author profile page (reached by clicking any author badge): the author's
  card — name, avatar, account-creation date and last-activity — above a **filterable**
  grid of the documents they own. Read-only and anonymous. Reuses the shared browse
  `filter-bar` (Type / Language / text search); the Author control is dropped, since the
  author is already fixed to this profile."
  (:require
   [clojure.string                       :as str]
   [landing.agora.document.kind          :as dk]
   [landing.agora.frontend.auth          :as auth]
   [landing.agora.frontend.document-page :as document-page]
   [landing.agora.frontend.fmt           :as fmt]
   [landing.agora.frontend.i18n          :as i18n]
   [landing.agora.frontend.modal         :as modal]
   [landing.language                     :as language]
   [re-frame.core                        :as rf]))

(defn- delete-message
  "What the confirmation says. `attributed-count` is the number of versions that will change byline —
  drafts included, which is why it comes from the server and not from the grid below: that grid shows
  the *published* ones, and someone whose work is all in progress reads there as having none.

  Nothing is destroyed, so the warning names what actually happens: those documents stay and become
  the anonymous person's."
  [lang attributed-count]
  (if (pos? (or attributed-count 0))
    (str (i18n/t lang :admin/person-delete-confirm)
         " " attributed-count
         " " (i18n/t lang :admin/person-delete-anonymises))
    (i18n/t lang :admin/person-delete-confirm)))

(defn- delete-person-button
  "Owner-only: remove this person. Always offered — what it costs is said in the confirmation rather
  than turned into a refusal, since keeping a stray account because it once signed something is not a
  decision to take on the administrator's behalf."
  [lang {:keys [id attributed-count]}]
  (let [refusal @(rf/subscribe [:agora/admin-person-error])]
    [:div {:style {:margin-top "0.6em"}}
     [:button {:on-click #(rf/dispatch [::modal/confirm
                                        {:message (delete-message lang attributed-count)
                                         :confirm-label (i18n/t lang :admin/person-delete)
                                         :on-confirm [:agora/admin-delete-person id]}])
               :style {:border "1px solid #c92a2a"
                       :background "transparent"
                       :color "#c92a2a"
                       :border-radius "0.3em"
                       :padding "0.2em 0.6em"
                       :font-size "0.8em"
                       :cursor "pointer"}}
      (i18n/t lang :admin/person-delete)]
     (when refusal
       [:span {:style {:color "#c92a2a"
                       :font-size "0.8em"
                       :margin-left "0.6em"}}
        (if (= "reserved person" (:error refusal))
          (i18n/t lang :admin/person-reserved)
          (i18n/t lang :admin/person-delete-failed))])]))

(defn author-page
  "Render an author profile map {:display-name :avatar-url :created-at :last-activity :member?
  :documents [...]}. A member is dated by when they joined; a credited person, who has no account
  here, carries the same badge the author list uses instead. The shared browse filter narrows the grid locally (Type / Language /
  search); the author is fixed, so that control is hidden."
  [{:keys [display-name avatar-url created-at last-activity documents member?]
    :as profile}]
  (let [lang @(rf/subscribe [::i18n/lang])
        admin? @(rf/subscribe [::auth/admin?])
        ;; the kinds / languages actually present, in canonical order — the filter's options
        kind-ids (filterv (into #{}
                                (keep #(some-> (:kind %)
                                               keyword))
                                documents)
                          dk/kind-ids)
        lang-ids (filterv (into #{}
                                (keep #(some-> (:lang %)
                                               name))
                                documents)
                          language/languages)
        filtered (document-page/filter-items documents)]
    [:div {:style {:max-width "72em"
                   :margin "1.5em auto"
                   :padding "0 0.8em"
                   :font-family "system-ui, sans-serif"}}
     ;; --- author card ---
     [:div {:style {:display "flex"
                    :align-items "center"
                    :gap "1em"
                    :margin-bottom "1.2em"}}
      (when-not (str/blank? avatar-url)
        [:img {:src avatar-url
               :alt ""
               :style {:width "3.6em"
                       :height "3.6em"
                       :border-radius "50%"
                       :object-fit "cover"}}])
      [:div
       [:h1 {:style {:margin 0
                     :font-size "1.6em"
                     :color "#2a2723"}}
        (or display-name (i18n/t lang :author/unknown))]
       [:div {:style {:color "#888"
                      :font-size "0.9em"
                      :margin-top "0.2em"}}
        ;; a member joined and answers for what they wrote; a credited person never had an account
        ;; here, so "member since" would be a date about us, not about them
        (if member?
          (when-let [c (fmt/utc created-at)] [:span (str (i18n/t lang :author/member-since) " " c)])
          [:span {:title (i18n/t lang :authors/cited-hint)
                  :style {:border "1px solid #d8d2c8"
                          :border-radius "0.8em"
                          :padding "0.05em 0.6em"
                          :font-size "0.85em"
                          :color "#8a8177"}}
           (i18n/t lang :authors/cited)])
        (when-let [a (fmt/utc last-activity)]
          [:span (str "  ·  " (i18n/t lang :author/last-activity) " " a)])]
       (when admin? [delete-person-button lang profile])]]
     ;; --- shared browse filter (Type / Language / search); Author control dropped ---
     [document-page/filter-bar lang kind-ids lang-ids {:author? false}]
     [:div {:style {:color "#888"
                    :font-size "0.82em"
                    :margin-bottom "0.8em"}}
      (str (count filtered) " / " (count documents) " " (i18n/t lang :author/kis))]
     ;; --- grid ---
     (if (seq filtered)
       (into [:div {:style {:display "grid"
                            :grid-template-columns "repeat(auto-fill, minmax(min(17em, 100%), 1fr))"
                            :gap "0.9em"}}]
             (for [k filtered] ^{:key (:id k)} [document-page/discover-card lang k]))
       [:p {:style {:color "#aaa"
                    :margin "1.5em 0"}}
        (i18n/t lang :author/no-kis)])]))
