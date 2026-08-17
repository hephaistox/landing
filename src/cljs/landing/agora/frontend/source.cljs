(ns landing.agora.frontend.source
  "Bibliographic works — authoring + display. A `work` is a document (`kind=work`): a shared
  bibliographic record (cited author, title, year, editor, url). An `extract` cites the one work it
  draws from, like any citation.

   - `work-modal` — pick an existing work (search the corpus) or create a new one (author
     person-picker + title/year/editor/url), used while authoring an extract to cite its work.

  Low-level (no dependency on the page namespaces), like `cite`."
  (:require
   [clojure.string                    :as str]
   [landing.agora.frontend.i18n       :as i18n]
   [landing.agora.frontend.ui-commons :as ui]
   [re-frame.core                     :as rf]
   [reagent.core                      :as r]))

;; --- tiny fetch helpers (raw fetch, like cite.cljs) -------------------------
(defn- GET*
  [url on-ok]
  (-> (js/fetch url #js {:headers #js {"Accept" "application/json"}})
      (.then #(.json %))
      (.then #(on-ok (js->clj % :keywordize-keys true)))
      (.catch (fn [_]))))

(defn- POST*
  [url body on-ok]
  (-> (js/fetch url
                #js {:method "POST"
                     :headers #js {"Content-Type" "application/json"
                                   "Accept" "application/json"}
                     :body (js/JSON.stringify (clj->js body))})
      (.then #(.json %))
      (.then #(on-ok (js->clj % :keywordize-keys true)))
      (.catch (fn [_]))))

;; --- styles -----------------------------------------------------------------
(def ^:private field
  {:width "100%"
   :box-sizing "border-box"
   :padding "0.4em 0.5em"
   :font-size "0.9em"
   :border "1px solid #ccc"
   :border-radius "0.3em"})
(def ^:private result-row
  {:display "block"
   :width "100%"
   :text-align "left"
   :padding "0.5em 0.6em"
   :border "none"
   :border-bottom "1px solid #f0f0f0"
   :background "#fff"
   :cursor "pointer"
   :font-size "0.9em"})

(defn work-label
  "One-line label of a work — cited author · title (year) · editor."
  [w]
  (str (:author-name w)
       " · "
       (:title w)
       (when (:year w) (str " (" (:year w) ")"))
       (when-not (str/blank? (:editor w)) (str " · " (:editor w)))))

;; --- a search-result card → the work shape the caller cites ------------------
(defn- card->work
  "A `kind=work` browse card → the work map the picker returns: identity (`:name`/`:major`/`:lang`,
  for the citation token) plus display fields, the cited author taken from the derived byline."
  [c]
  {:name (:name c)
   :major (:major c)
   :lang (:lang c)
   :title (:title c)
   :author-name (:attributed-author c)
   :author-id (:attributed-author-id c)
   :year (:year c)
   :editor (:editor c)
   :url (:url c)})

;; --- author person-picker (inside 'create work') ----------------------------
(defn- person-picker
  "Pick or create the work's cited author. Calls `on-pick` with {:id :display-name}."
  [_on-pick]
  (let [q (r/atom "")
        results (r/atom [])
        busy? (r/atom false)]
    (fn [on-pick]
      (let [lang @(rf/subscribe [::i18n/lang])]
        [:div {:style {:position "relative"}}
         [ui/composed-field {:type "text"
                             :value @q
                             :placeholder (i18n/t lang :source/author-ph)
                             :style field
                             :on-text (fn [v]
                                        (reset! q v)
                                        (if (str/blank? v)
                                          (reset! results [])
                                          (GET* (str "/agora/api/people?q="
                                                     (js/encodeURIComponent v))
                                                #(reset! results %))))}]
         (when (seq @results)
           (into [:div {:style {:border "1px solid #ddd"
                                :border-radius "0.3em"
                                :max-height "10em"
                                :overflow-y "auto"
                                :margin-top "0.2em"}}]
                 (for [p @results]
                   ^{:key (:id p)}
                   [:button {:style result-row
                             :on-click #(on-pick p)}
                    (:display-name p)])))
         (when (and (not (str/blank? @q)) (empty? @results))
           [:button {:disabled @busy?
                     :style (assoc result-row :color "#b9770e" :font-weight 700)
                     :on-click (fn []
                                 (reset! busy? true)
                                 (POST* "/agora/api/people"
                                        {:display-name @q}
                                        (fn [p] (reset! busy? false) (on-pick p))))}
            (str "＋ " (i18n/t lang :source/new-person) " “" @q "”")])]))))

;; --- the dedicated 'cite a work' modal --------------------------------------
(defn work-modal
  "A modal to cite a work: find an existing one (search the corpus) or create a new one (author +
  title/year/editor/url). `on-pick` receives the picked/created work (`card->work` shape); `on-close`
  dismisses. `publication-id` scopes the search and gathers a newly-created work as a draft in it."
  [_on-pick _on-close _lang _publication-id]
  (let [mode (r/atom :search) ; :search | :create
        q (r/atom "")
        results (r/atom [])
        author (r/atom nil)
        draft (r/atom {}) ; {:title :year :editor :url}
        busy? (r/atom false)]
    (fn [on-pick on-close lang publication-id]
      (let [run-search
            (fn [text]
              (reset! q text)
              (if (str/blank? text)
                (reset! results [])
                (GET* (str "/agora/api/documents/ki?lang="
                           (name lang)
                           "&q="
                           (js/encodeURIComponent text)
                           (when (seq publication-id)
                             (str "&publication-id=" (js/encodeURIComponent publication-id))))
                      (fn [cards]
                        (reset! results (into []
                                              (comp (filter #(= "work" (:kind %))) (map card->work))
                                              cards))))))
            create! (fn []
                      (reset! busy? true)
                      (POST* "/agora/api/documents/ki"
                             {:kind "work"
                              :title (:title @draft)
                              :text ""
                              :lang (name lang)
                              :attributed-author-id (:id @author)
                              :attributed-author (:display-name @author)
                              :year (let [y (:year @draft)] (when-not (str/blank? y) y))
                              :editor (:editor @draft)
                              :url (:url @draft)
                              :publication-id publication-id}
                             (fn [w] (reset! busy? false) (on-pick (card->work w)))))
            can-create? (and @author (not (str/blank? (:title @draft))))]
        [:div {:on-click on-close
               :style {:position "fixed"
                       :inset 0
                       :z-index 200
                       :background "rgba(0,0,0,0.45)"
                       :display "flex"
                       :align-items "flex-start"
                       :justify-content "center"
                       :padding-top "9vh"}}
         [ui/on-escape on-close]
         [:div {:on-click #(.stopPropagation %)
                :style {:width "36em"
                        :max-width "94%"
                        :max-height "80vh"
                        :overflow-y "auto"
                        :background "#fff"
                        :border-radius "0.6em"
                        :padding "1.2em 1.4em"
                        :font-family "system-ui, sans-serif"}}
          [:div {:style {:display "flex"
                         :gap "0.5em"
                         :margin-bottom "0.9em"}}
           [:h3 {:style {:margin 0
                         :font-size "1.1em"
                         :flex 1}}
            (i18n/t lang (if (= @mode :search) :source/find-title :source/create-title))]
           [:button {:on-click #(reset! mode (if (= @mode :search) :create :search))
                     :style {:border "1px solid #b9770e"
                             :background "#fff"
                             :color "#b9770e"
                             :border-radius "0.3em"
                             :padding "0.25em 0.7em"
                             :cursor "pointer"
                             :font-size "0.85em"}}
            (i18n/t lang (if (= @mode :search) :source/create-new :source/find-existing))]
           [:button {:on-click on-close
                     :style {:border "none"
                             :background "transparent"
                             :cursor "pointer"
                             :font-size "1.1em"}}
            "✕"]]
          (if (= @mode :search)
            ;; ---- find an existing work ----
            [:div
             [ui/composed-field {:type "text"
                                 :placeholder (i18n/t lang :source/find)
                                 :style (assoc field :margin-bottom "0.6em")
                                 :value @q
                                 :on-text run-search}]
             (if (seq @results)
               (into [:div {:style {:border "1px solid #eee"
                                    :border-radius "0.4em"}}]
                     (for [[i w] (map-indexed vector @results)]
                       ^{:key (str (:name w) "-" i)}
                       [:button {:style result-row
                                 :on-click #(on-pick w)}
                        (work-label w)]))
               (when-not (str/blank? @q)
                 [:p {:style {:color "#aaa"
                              :font-size "0.9em"}}
                  (i18n/t lang :source/no-results)]))]
            ;; ---- create a new work ----
            [:div
             [:div {:style {:font-size "0.8em"
                            :color "#555"
                            :margin-bottom "0.2em"}}
              (i18n/t lang :source/author)]
             (if @author
               [:div {:style {:display "flex"
                              :align-items "center"
                              :gap "0.5em"
                              :margin-bottom "0.6em"}}
                [:b (:display-name @author)]
                [:button {:on-click #(reset! author nil)
                          :style {:border "none"
                                  :background "transparent"
                                  :color "#c92a2a"
                                  :cursor "pointer"}}
                 "✕"]]
               [:div {:style {:margin-bottom "0.6em"}}
                [person-picker #(reset! author %)]])
             [ui/composed-field {:type "text"
                                 :placeholder (i18n/t lang :source/title)
                                 :value (or (:title @draft) "")
                                 :style (assoc field :margin-bottom "0.5em")
                                 :on-text #(swap! draft assoc :title %)}]
             [:div {:style {:display "flex"
                            :gap "0.5em"
                            :margin-bottom "0.7em"}}
              [:input {:type "number"
                       :placeholder (i18n/t lang :source/year)
                       :style field
                       :value (or (:year @draft) "")
                       :on-change #(swap! draft assoc :year (.. % -target -value))}]
              [ui/composed-field {:type "text"
                                  :placeholder (i18n/t lang :source/editor)
                                  :style field
                                  :value (or (:editor @draft) "")
                                  :on-text #(swap! draft assoc :editor %)}]]
             [:input {:type "url"
                      :placeholder (i18n/t lang :source/url-ph)
                      :style (assoc field :margin-bottom "0.7em")
                      :value (or (:url @draft) "")
                      :on-change #(swap! draft assoc :url (.. % -target -value))}]
             [:button {:disabled (or @busy? (not can-create?))
                       :style {:padding "0.45em 1em"
                               :border "none"
                               :background "#b9770e"
                               :color "#fff"
                               :border-radius "0.3em"
                               :cursor (if can-create? "pointer" "default")
                               :opacity (if can-create? 1 0.5)}
                       :on-click #(when can-create? (create!))}
              (i18n/t lang :source/add)]])]]))))

;; --- the bibliographic fields of a work (authoring) -------------------------
(defn work-fields
  "The bibliographic fields of a `work` (cited-author person-picker + year / editor / url), bound to
  `value` (`{:author-id :author-name :year :editor :url}`); `on-set` is called `(on-set key value)` per
  change. The title is the form's own title field, so it is not repeated here."
  [value on-set]
  (let [lang @(rf/subscribe [::i18n/lang])]
    [:div
     [:div {:style {:font-size "0.8em"
                    :color "#555"
                    :margin "0.3em 0 0.2em"}}
      (i18n/t lang :source/author)]
     (if (:author-id value)
       [:div {:style {:display "flex"
                      :align-items "center"
                      :gap "0.5em"
                      :margin-bottom "0.6em"}}
        [:b (:author-name value)]
        [:button {:on-click #(do (on-set :author-id nil) (on-set :author-name nil))
                  :style {:border "none"
                          :background "transparent"
                          :color "#c92a2a"
                          :cursor "pointer"}}
         "✕"]]
       [:div {:style {:margin-bottom "0.6em"}}
        [person-picker
         (fn [p] (on-set :author-id (:id p)) (on-set :author-name (:display-name p)))]])
     [:div {:style {:display "flex"
                    :gap "0.5em"
                    :margin-bottom "0.6em"}}
      [:input {:type "number"
               :placeholder (i18n/t lang :source/year)
               :style field
               :value (or (:year value) "")
               :on-change #(on-set :year (.. % -target -value))}]
      [ui/composed-field {:type "text"
                          :placeholder (i18n/t lang :source/editor)
                          :style field
                          :value (or (:editor value) "")
                          :on-text #(on-set :editor %)}]]
     [:input {:type "url"
              :placeholder (i18n/t lang :source/url-ph)
              :style field
              :value (or (:url value) "")
              :on-change #(on-set :url (.. % -target -value))}]]))
