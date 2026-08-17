(ns landing.agora.frontend.author-page
  "The public author profile page (reached by clicking any author badge): the author's
  card — name, avatar, account-creation date and last-activity — above a **filterable**
  grid of the documents they own. Read-only and anonymous. Reuses the shared browse
  `filter-bar` (Type / Language / text search); the Author control is dropped, since the
  author is already fixed to this profile."
  (:require
   [clojure.string                       :as str]
   [landing.agora.document.kind          :as dk]
   [landing.agora.frontend.document-page :as document-page]
   [landing.agora.frontend.fmt           :as fmt]
   [landing.agora.frontend.i18n          :as i18n]
   [landing.language                     :as language]
   [re-frame.core                        :as rf]))

(defn author-page
  "Render an author profile map {:display-name :avatar-url :created-at :last-activity
  :documents [...]}. The shared browse filter narrows the grid locally (Type / Language /
  search); the author is fixed, so that control is hidden."
  [{:keys [display-name avatar-url created-at last-activity documents]}]
  (let [lang @(rf/subscribe [::i18n/lang])
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
        (when-let [c (fmt/utc created-at)] [:span (str (i18n/t lang :author/member-since) " " c)])
        (when-let [a (fmt/utc last-activity)]
          [:span (str "  ·  " (i18n/t lang :author/last-activity) " " a)])]]]
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
