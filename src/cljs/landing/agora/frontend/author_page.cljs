(ns landing.agora.frontend.author-page
  "The public author profile page (reached by clicking any author badge): the author's
  card — name, avatar, account-creation date and last-activity — above a **searchable**
  grid of the documents they own. Read-only and anonymous. Intentionally small; it grows
  richer as more per-author signals (objections, confidence, forks) are implemented."
  (:require
   [clojure.string                       :as str]
   [landing.agora.frontend.cite          :as cite]
   [landing.agora.frontend.document-page :as document-page]
   [landing.agora.frontend.fmt           :as fmt]
   [landing.agora.frontend.i18n          :as i18n]
   [re-frame.core                        :as rf]
   [reagent.core                         :as r]))

(defn- matches?
  "Client-side filter for the document list: case-insensitive substring over title/name/text."
  [q node]
  (let [q (str/lower-case (str/trim q))]
    (or (str/blank? q)
        (-> (str (:title node) " " (:name node) " " (cite/node-text node))
            str/lower-case
            (str/includes? q)))))

(defn author-page
  "Render an author profile map {:display-name :avatar-url :created-at :last-activity
  :documents [...]}. The search box filters the grid locally."
  [_author]
  (let [q (r/atom "")]
    (fn [{:keys [display-name avatar-url created-at last-activity documents]}]
      (let [lang @(rf/subscribe [::i18n/lang])
            filtered (filterv #(matches? @q %) documents)]
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
            (when-let [c (fmt/utc created-at)]
              [:span (str (i18n/t lang :author/member-since) " " c)])
            (when-let [a (fmt/utc last-activity)]
              [:span (str "  ·  " (i18n/t lang :author/last-activity) " " a)])]]]
         ;; --- search ---
         [:input {:type "text"
                  :placeholder (i18n/t lang :author/search-ph)
                  :value @q
                  :on-change #(reset! q (.. % -target -value))
                  :style {:width "100%"
                          :box-sizing "border-box"
                          :padding "0.55em 0.7em"
                          :font-size "0.95em"
                          :border "1px solid #ccc"
                          :border-radius "0.4em"
                          :margin-bottom "0.5em"}}]
         [:div {:style {:color "#888"
                        :font-size "0.82em"
                        :margin-bottom "0.8em"}}
          (str (count filtered) " / " (count documents) " " (i18n/t lang :author/kis))]
         ;; --- grid ---
         (if (seq filtered)
           (into [:div {:style {:display "grid"
                                :grid-template-columns
                                "repeat(auto-fill, minmax(min(17em, 100%), 1fr))"
                                :gap "0.9em"}}]
                 (for [k filtered] ^{:key (:id k)} [document-page/discover-card lang k]))
           [:p {:style {:color "#aaa"
                        :margin "1.5em 0"}}
            (i18n/t lang :author/no-kis)])]))))
