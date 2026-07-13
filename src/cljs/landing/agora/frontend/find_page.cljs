(ns landing.agora.frontend.find-page
  "Two public browse views: **by author** (`/agora/<lang>/authors`, search people →
  their profile) and **by source** (`/agora/<lang>/sources`, search the shared works in
  `AGORA_SOURCE`). Both self-fetch from the existing search endpoints (`/agora/api/people`,
  `/agora/api/source`) via local state, like the source picker — no re-frame plumbing."
  (:require
   [clojure.string                    :as str]
   [landing.agora.frontend.i18n       :as i18n]
   [landing.agora.frontend.ui-commons :as ui]
   [re-frame.core                     :as rf]
   [reagent.core                      :as r]))

(defn- GET*
  [url on-ok]
  (-> (js/fetch url #js {:headers #js {"Accept" "application/json"}})
      (.then #(.json %))
      (.then #(on-ok (js->clj % :keywordize-keys true)))
      (.catch (fn [_] (on-ok [])))))

(def ^:private page-style
  {:max-width "44em"
   :margin "1.5em auto"
   :padding "0 1em"
   :font-family "system-ui, sans-serif"})

(def ^:private field
  {:width "100%"
   :box-sizing "border-box"
   :padding "0.5em 0.6em"
   :font-size "0.95em"
   :border "1px solid #ccc"
   :border-radius "0.3em"})

(def ^:private link-style
  {:color "#b9770e"
   :text-decoration "none"
   :font-weight 600})

;; --- browse by author -------------------------------------------------------
(defn authors-page
  "Search people by name; each result links to their public author profile."
  []
  (r/with-let
   [q (r/atom "") results (r/atom [])]
   (let [lang @(rf/subscribe [::i18n/lang])]
     [:div {:style page-style}
      [:h1 {:style {:font-size "1.4em"
                    :margin "0 0 0.2em"}}
       (i18n/t lang :authors/title)]
      [:p {:style {:color "#777"
                   :margin "0 0 1em"}}
       (i18n/t lang :authors/lead)]
      [ui/composed-field {:type "text"
                          :autoFocus true
                          :placeholder (i18n/t lang :authors/search-ph)
                          :value @q
                          :style field
                          :on-text (fn [v]
                                     (reset! q v)
                                     (if (str/blank? v)
                                       (reset! results [])
                                       (GET* (str "/agora/api/people?q=" (js/encodeURIComponent v))
                                             #(reset! results %))))}]
      (cond
        (and (not (str/blank? @q)) (empty? @results)) [:p {:style {:color "#aaa"
                                                                   :margin-top "1em"}}
                                                       (i18n/t lang :authors/none)]
        (seq @results) (into [:ul {:style {:margin "1em 0 0"
                                           :padding-left "1.2em"
                                           :line-height "1.9"}}]
                             (for [p @results]
                               ^{:key (:id p)}
                               [:li
                                [:a {:href (i18n/author lang (:id p))
                                     :style link-style}
                                 (:display-name p)]])))])))

;; --- browse by source -------------------------------------------------------
(defn sources-page
  "Search the shared works (`AGORA_SOURCE`) by author / title / year; each result shows the
  work, its author (→ profile) and a link when it has a URL."
  []
  (r/with-let
   [filters (r/atom {}) results (r/atom [])]
   (let [lang @(rf/subscribe [::i18n/lang])
         run!
         (fn []
           (let [{:keys [author title year]} @filters
                 qs (->> [(when-not (str/blank? author)
                            (str "author=" (js/encodeURIComponent author)))
                          (when-not (str/blank? title) (str "title=" (js/encodeURIComponent title)))
                          (when-not (str/blank? year) (str "year=" (js/encodeURIComponent year)))]
                         (remove nil?)
                         (str/join "&"))]
             (if (str/blank? qs)
               (reset! results [])
               (GET* (str "/agora/api/source?" qs) #(reset! results %)))))
         set-f (fn [k v] (swap! filters assoc k v) (run!))]
     [:div {:style page-style}
      [:h1 {:style {:font-size "1.4em"
                    :margin "0 0 0.2em"}}
       (i18n/t lang :sources/browse-title)]
      [:p {:style {:color "#777"
                   :margin "0 0 1em"}}
       (i18n/t lang :sources/browse-lead)]
      [:div {:style {:display "flex"
                     :gap "0.5em"}}
       [ui/composed-field {:type "text"
                           :placeholder (i18n/t lang :source/author)
                           :style field
                           :value (or (:author @filters) "")
                           :on-text #(set-f :author %)}]
       [ui/composed-field {:type "text"
                           :placeholder (i18n/t lang :source/title)
                           :style field
                           :value (or (:title @filters) "")
                           :on-text #(set-f :title %)}]
       [:input {:type "number"
                :placeholder (i18n/t lang :source/year)
                :style (assoc field :max-width "7em")
                :on-change #(set-f :year (.. % -target -value))}]]
      (if (seq @results)
        (into [:ul {:style {:margin "1em 0 0"
                            :padding-left "1.2em"
                            :line-height "1.7"}}]
              (for [s @results]
                ^{:key (:id s)}
                [:li
                 [:span {:style {:font-weight 600}}
                  (:title s)]
                 (when (:year s) (str " (" (:year s) ")"))
                 " — "
                 (if (:author-id s)
                   [:a {:href (i18n/author lang (:author-id s))
                        :style link-style}
                    (:author-name s)]
                   [:span (:author-name s)])
                 (when-not (str/blank? (:editor s)) (str " · " (:editor s)))
                 (when-not (str/blank? (:url s))
                   [:span
                    " · "
                    [:a {:href (:url s)
                         :target "_blank"
                         :rel "noopener noreferrer"
                         :style link-style}
                     (i18n/t lang :source/link)]])]))
        [:p {:style {:color "#aaa"
                     :margin-top "1em"}}
         (i18n/t lang :sources/browse-none)])])))
