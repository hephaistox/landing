(ns landing.agora.frontend.source
  "The one bibliographic source of a document — authoring + display, shared by the KI and
  article forms.

  A document cites **at most one** source (`{:id :title :year :editor :author-name
  :author-id :locator}`) — a source *work* document, `:id` = its cid, plus a `:locator`
  (page/entry).
  Two sources are two pieces of knowledge → two documents; combining them is an inference
  that stands apart. Three ways to set it: a **recent-source chip** (one click), the
  **\"Find a source\" search modal** (author/title/year filters), or **creating a new
  source** (with an author person-picker). `strip-source` reduces it to the
  `{:source-id :locator}` the API stores. Low-level (no dependency on the page
  namespaces), like `cite`."
  (:require
   [clojure.string                    :as str]
   [landing.agora.frontend.i18n       :as i18n]
   [landing.agora.frontend.ui-commons :as ui]
   [re-frame.core                     :as rf]
   [reagent.core                      :as r]))

;; --- persistence shape ------------------------------------------------------
(defn strip-source
  "Reduce the editor's resolved source (or nil) to what the API persists:
  `{:source-id :locator}`. nil → `{:source-id \"\"}`, the explicit **clear** sentinel: a
  blank id clears the snapshot server-side, whereas an *absent* `:source` key would carry
  the old one forward — so the forms always send an explicit value."
  [src]
  (if (:id src)
    {:source-id (:id src)
     :locator (:locator src)}
    {:source-id ""}))

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
(def ^:private chip
  {:border "1px solid #cfe0e3"
   :background "#f2f8f9"
   :color "#0b7285"
   :border-radius "1em"
   :padding "0.15em 0.7em"
   :margin "0 0.3em 0.3em 0"
   :font-size "0.82em"
   :cursor "pointer"})

(defn- source-label
  "One-line label of a source/ref for a result row or chip."
  [s]
  (str (:author-name s)
       " · "
       (:title s)
       (when (:year s) (str " (" (:year s) ")"))
       (when-not (str/blank? (:editor s)) (str " · " (:editor s)))))

;; --- author person-picker (inside 'create source') --------------------------
(defn- person-picker
  "Pick or create the source's author. Calls `on-pick` with {:id :display-name}."
  [_on-pick]
  (let [q (r/atom "")
        results (r/atom [])
        busy? (r/atom false)]
    (fn [on-pick]
      (let [lang @(rf/subscribe [::i18n/lang])]
        [:div {:style {:position "relative"}}
         [:input {:type "text"
                  :value @q
                  :placeholder (i18n/t lang :source/author-ph)
                  :style field
                  :on-change (fn [e]
                               (let [v (.. e -target -value)]
                                 (reset! q v)
                                 (if (str/blank? v)
                                   (reset! results [])
                                   (GET* (str "/agora/api/people?q=" (js/encodeURIComponent v))
                                         #(reset! results %)))))}]
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

;; --- the dedicated 'Find a source' modal ------------------------------------
(defn source-search-modal
  "A modal to find an existing source (author/title/year filters) or create a new one.
  `on-pick` receives a resolved source; `on-close` dismisses."
  [_on-pick _on-close]
  (let [mode (r/atom :search)   ; :search | :create
        filters (r/atom {})     ; {:author :title :year}
        results (r/atom [])
        author (r/atom nil)     ; picked author for :create/:edit
        draft (r/atom {})       ; {:title :year :editor} for :create/:edit
        editing-id (r/atom nil) ; source id when editing an existing one, else nil
        busy? (r/atom false)
        start-edit (fn [s]      ; load a search result into the create/edit form
                     (reset! editing-id (:id s))
                     (reset! author {:id (:author-id s)
                                     :display-name (:author-name s)})
                     (reset! draft {:title (:title s)
                                    :year (str (or (:year s) ""))
                                    :editor (or (:editor s) "")}))]
    (fn [on-pick on-close]
      (let [lang @(rf/subscribe [::i18n/lang])
            run-search (fn []
                         (let [{:keys [author title year]} @filters
                               qs (->> [(when-not (str/blank? author)
                                          (str "author=" (js/encodeURIComponent author)))
                                        (when-not (str/blank? title)
                                          (str "title=" (js/encodeURIComponent title)))
                                        (when-not (str/blank? year)
                                          (str "year=" (js/encodeURIComponent year)))]
                                       (remove nil?)
                                       (str/join "&"))]
                           (GET* (str "/agora/api/source?" qs) #(reset! results %))))
            set-f (fn [k e] (swap! filters assoc k (.. e -target -value)) (run-search))]
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
            (i18n/t lang
                    (cond
                      @editing-id :source/edit-title
                      (= @mode :search) :source/find-title
                      :else :source/create-title))]
           [:button {:on-click (fn []
                                 ;; toggling to a fresh create clears any edit-in-progress
                                 (when (= @mode :search)
                                   (reset! editing-id nil)
                                   (reset! author nil)
                                   (reset! draft {}))
                                 (reset! mode (if (= @mode :search) :create :search)))
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
            ;; ---- search existing ----
            [:div
             [:div {:style {:display "flex"
                            :gap "0.5em"
                            :margin-bottom "0.6em"}}
              [:input {:type "text"
                       :placeholder (i18n/t lang :source/author)
                       :style field
                       :on-change #(set-f :author %)}]
              [:input {:type "text"
                       :placeholder (i18n/t lang :source/title)
                       :style field
                       :on-change #(set-f :title %)}]
              [:input {:type "number"
                       :placeholder (i18n/t lang :source/year)
                       :style (assoc field :max-width "6em")
                       :on-change #(set-f :year %)}]]
             (if (seq @results)
               (into [:div {:style {:border "1px solid #eee"
                                    :border-radius "0.4em"}}]
                     (for [s @results]
                       ^{:key (:id s)}
                       [:div {:style {:display "flex"
                                      :align-items "center"
                                      :border-bottom "1px solid #f0f0f0"}}
                        [:button {:style (assoc result-row :flex 1 :border-bottom "none")
                                  :on-click #(on-pick s)}
                         (source-label s)]
                        [:button {:title (i18n/t lang :source/edit-title)
                                  :on-click #(do (start-edit s) (reset! mode :create))
                                  :style {:border "none"
                                          :background "transparent"
                                          :cursor "pointer"
                                          :color "#b9770e"
                                          :padding "0 0.6em"}}
                         "✎"]]))
               [:p {:style {:color "#aaa"
                            :font-size "0.9em"}}
                (i18n/t lang :source/no-results)])]
            ;; ---- create new ----
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
             [:input {:type "text"
                      :placeholder (i18n/t lang :source/title)
                      :value (or (:title @draft) "")
                      :style (assoc field :margin-bottom "0.5em")
                      :on-change #(swap! draft assoc :title (.. % -target -value))}]
             [:div {:style {:display "flex"
                            :gap "0.5em"
                            :margin-bottom "0.7em"}}
              [:input {:type "number"
                       :placeholder (i18n/t lang :source/year)
                       :style field
                       :value (or (:year @draft) "")
                       :on-change #(swap! draft assoc :year (.. % -target -value))}]
              [:input {:type "text"
                       :placeholder (i18n/t lang :source/editor)
                       :style field
                       :value (or (:editor @draft) "")
                       :on-change #(swap! draft assoc :editor (.. % -target -value))}]]
             [:button
              {:disabled (or @busy? (nil? @author) (str/blank? (:title @draft)))
               :style {:padding "0.45em 1em"
                       :border "none"
                       :background "#b9770e"
                       :color "#fff"
                       :border-radius "0.3em"
                       :cursor
                       (if (and @author (not (str/blank? (:title @draft)))) "pointer" "default")
                       :opacity (if (and @author (not (str/blank? (:title @draft)))) 1 0.5)}
               :on-click
               (fn []
                 (reset! busy? true)
                 (POST* (if @editing-id (str "/agora/api/source/" @editing-id) "/agora/api/source")
                        {:person-id (:id @author)
                         :title (:title @draft)
                         :year (let [y (:year @draft)] (when-not (str/blank? y) (js/parseInt y 10)))
                         :editor (:editor @draft)}
                        (fn [s] (reset! busy? false) (reset! editing-id nil) (on-pick s))))}
              (i18n/t lang (if @editing-id :source/save :source/add))]])]]))))

;; --- the single-source editor -----------------------------------------------
(defn source-editor
  "Set (or clear) the document's one source. `value` = current source map (or nil);
  `set-source!` gets the new source (a map) or nil on every change."
  [_value _set-source!]
  (let [open? (r/atom false)
        recent (r/atom [])
        loaded? (r/atom false)]
    (fn [value set-source!]
      (let [lang @(rf/subscribe [::i18n/lang])]
        (when-not @loaded?
          (reset! loaded? true)
          (GET* "/agora/api/source/recent" #(reset! recent %)))
        [:div
         [:div {:style {:font-size "0.8em"
                        :color "#555"
                        :margin-bottom "0.3em"}}
          (i18n/t lang :source/heading)]
         (if (:id value)
           ;; a source is set — show it, its locator, and a remove control
           [:div {:style {:display "flex"
                          :align-items "center"
                          :gap "0.5em"
                          :margin-bottom "0.35em"}}
            [:span {:style {:flex "1 1 auto"
                            :font-size "0.88em"}}
             (source-label value)]
            [:input {:type "text"
                     :value (or (:locator value) "")
                     :placeholder (i18n/t lang :source/locator-ph)
                     :style (assoc field :max-width "12em")
                     :on-change #(set-source! (assoc value :locator (.. % -target -value)))}]
            [:button {:title (i18n/t lang :ref/remove)
                      :on-click #(set-source! nil)
                      :style {:border "none"
                              :background "transparent"
                              :color "#c92a2a"
                              :cursor "pointer"}}
             "✕"]]
           ;; no source yet — recent chips + the "find a source" modal
           [:div
            (when (seq @recent)
              [:div {:style {:margin "0.3em 0"}}
               [:span {:style {:font-size "0.78em"
                               :color "#8a7a55"
                               :margin-right "0.3em"}}
                (i18n/t lang :source/recent)]
               (into [:span]
                     (for [s @recent]
                       ^{:key (:id s)}
                       [:button {:style chip
                                 :title (source-label s)
                                 :on-click #(set-source! (assoc s :locator ""))}
                        (:title s)
                        (when (:year s) (str " " (:year s)))]))])
            [:button {:on-click #(reset! open? true)
                      :style {:border "1px dashed #b9770e"
                              :background "transparent"
                              :color "#b9770e"
                              :border-radius "0.3em"
                              :padding "0.35em 0.8em"
                              :cursor "pointer"
                              :font-size "0.88em"
                              :margin-top "0.2em"}}
             (str "🔎 " (i18n/t lang :source/find))]])
         (when @open?
           [source-search-modal
            (fn [s] (set-source! (assoc s :locator "")) (reset! open? false))
            #(reset! open? false)])]))))

;; --- read-only display on the page ------------------------------------------
(defn source-view
  "Render a document's one resolved source under the card (author → profile link,
  title, year, editor, locator). Nothing when there is none."
  [src]
  (when (:id src)
    (let [lang @(rf/subscribe [::i18n/lang])]
      [:div {:style {:margin-top "1.1em"}}
       [:div {:style {:font-weight 700
                      :color "#8a7a55"
                      :font-size "0.82em"
                      :text-transform "uppercase"
                      :letter-spacing "0.04em"
                      :margin-bottom "0.35em"}}
        (i18n/t lang :source/heading)]
       [:div {:style {:color "#555"
                      :font-size "0.9em"
                      :line-height "1.5"}}
        (if (:author-id src)
          [:a {:href (i18n/author lang (:author-id src))
               :style {:color "#b9770e"
                       :text-decoration "none"
                       :font-weight 600}}
           (:author-name src)]
          [:span {:style {:font-weight 600}}
           (:author-name src)])
        " · "
        (:title src)
        (when (:year src) (str " (" (:year src) ")"))
        (when-not (str/blank? (:editor src)) (str " · " (:editor src)))
        (when-not (str/blank? (:locator src))
          [:span {:style {:color "#888"}}
           (str " — " (:locator src))])]])))
