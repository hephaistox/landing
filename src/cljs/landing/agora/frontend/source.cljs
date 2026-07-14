(ns landing.agora.frontend.source
  "Bibliographic sources — authoring + display. Two roles (see agora/CLAUDE.md §Sources):

   1. **A source-editor for a `kind=source` KI** (a *quotation*): pick/create the shared
      **work** (`AGORA_SOURCE` — author / title / year / editor / url) via the `\"Find a
      source\" search modal` (or a recent chip), plus this quotation's own `:locator`.
      `strip-source` reduces it to the `{:source-id :locator}` the API stores. Shown by the
      forms **only when kind=source** — other KIs never *attach* a source.

   2. **Quotes for a KI that quotes sources**: a KI relates to a source by *quoting* a
      `kind=source` KI (an edge-only input; `cite`'s search box routes such a pick to
      `on-quote`). `add-quote`/`strip-quotes` manage the list, `quotes-list` renders the
      authoring chips, `quotes-view` the read-page list (title · work-author · locator).

  Low-level (no dependency on the page namespaces), like `cite`."
  (:require
   [clojure.string                    :as str]
   [landing.agora.frontend.auth       :as auth]
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

;; --- quotes (a KI quoting kind=source KIs) ----------------------------------
(defn strip-quotes
  "Reduce the editor's quotes (resolved source-KIs) to the `[{:name :major}…]` the API stores
  (they become edge-only inputs)."
  [quotes]
  (mapv #(select-keys % [:name :major]) (or quotes [])))

(defn add-quote
  "Add a picked source-KI (a search-result card) to `quotes`, deduped by (name, major).
  Keeps display fields (`:title`, work `:author-name`) for the chip; `strip-quotes` drops them."
  [quotes k]
  (let [q {:name (:name k)
           :major (:major k)
           :title (:title k)
           :author-name (:author-name (:source k))}]
    (if (some #(and (= (:name %) (:name q)) (= (:major %) (:major q))) quotes)
      (vec quotes)
      (conj (vec quotes) q))))

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

;; --- the dedicated 'Find a source' modal ------------------------------------
(defn source-search-modal
  "A modal to find an existing source (author/title/year filters) or create a new one.
  `on-pick` receives a resolved source; `on-close` dismisses."
  [_on-pick _on-close & [to-edit]]
  (let [mode (r/atom (if to-edit :create :search)) ; :search | :create
        filters (r/atom {}) ; {:author :title :year}
        results (r/atom [])
        ;; `to-edit` (optional) opens the modal straight into editing that source
        author (r/atom (when to-edit
                         {:id (:author-id to-edit)
                          :display-name (:author-name to-edit)}))
        draft (r/atom (if to-edit
                        {:title (:title to-edit)
                         :year (str (or (:year to-edit) ""))
                         :editor (or (:editor to-edit) "")
                         :url (or (:url to-edit) "")}
                        {}))
        editing-id (r/atom (:id to-edit)) ; source id when editing an existing one, else nil
        busy? (r/atom false)
        start-edit (fn [s] ; load a search result into the create/edit form
                     (reset! editing-id (:id s))
                     (reset! author {:id (:author-id s)
                                     :display-name (:author-name s)})
                     (reset! draft {:title (:title s)
                                    :year (str (or (:year s) ""))
                                    :editor (or (:editor s) "")
                                    :url (or (:url s) "")}))]
    (fn [on-pick on-close & _]
      (let [lang @(rf/subscribe [::i18n/lang])
            user @(rf/subscribe [::auth/user])
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
            set-f (fn [k v] (swap! filters assoc k v) (run-search))]
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
                       :style (assoc field :max-width "6em")
                       :on-change #(set-f :year (.. % -target -value))}]]
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
                        ;; editing a shared source is restricted to the account that created it
                        (when (= (:id user) (:owner-id s))
                          [:button {:title (i18n/t lang :source/edit-title)
                                    :on-click #(do (start-edit s) (reset! mode :create))
                                    :style {:border "none"
                                            :background "transparent"
                                            :cursor "pointer"
                                            :color "#b9770e"
                                            :padding "0 0.6em"}}
                           "✎"])]))
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
                         :editor (:editor @draft)
                         :url (:url @draft)}
                        (fn [s] (reset! busy? false) (reset! editing-id nil) (on-pick s))))}
              (i18n/t lang (if @editing-id :source/save :source/add))]])]]))))

;; --- the single-source editor -----------------------------------------------
(defn source-editor
  "Set (or clear) the document's one source. `value` = current source map (or nil);
  `set-source!` gets the new source (a map) or nil on every change."
  [_value _set-source!]
  (let [open? (r/atom false)
        edit? (r/atom false) ; editing the current source's shared work fields (owner only)
        recent (r/atom [])
        loaded? (r/atom false)]
    (fn [value set-source!]
      (let [lang @(rf/subscribe [::i18n/lang])
            user @(rf/subscribe [::auth/user])]
        (when-not @loaded?
          (reset! loaded? true)
          (GET* "/agora/api/source-recent" #(reset! recent %)))
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
            [ui/composed-field {:type "text"
                                :value (or (:locator value) "")
                                :placeholder (i18n/t lang :source/locator-ph)
                                :style (assoc field :max-width "12em")
                                :on-text #(set-source! (assoc value :locator %))}]
            ;; edit the shared work's fields — only its owner (created_by) may
            (when (= (:id user) (:owner-id value))
              [:button {:title (i18n/t lang :source/edit-title)
                        :on-click #(reset! edit? true)
                        :style {:border "none"
                                :background "transparent"
                                :color "#b9770e"
                                :cursor "pointer"}}
               "✎"])
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
         (when (or @open? @edit?)
           [source-search-modal
            (fn [s]
              ;; keep the document's locator; editing the work only changes the shared fields
              (set-source! (assoc s :locator (or (:locator value) "")))
              (reset! open? false)
              (reset! edit? false))
            #(do (reset! open? false) (reset! edit? false))
            (when @edit? value)])]))))

;; --- quotes chosen while authoring ------------------------------------------
(defn quotes-list
  "The source quotes chosen for a document (authoring): each shows the quoted source-KI's title
  + its work author, with a remove ✕. Adding is done via the citation search box (`on-quote`)."
  [quotes set-quotes!]
  (when (seq quotes)
    (let [lang @(rf/subscribe [::i18n/lang])
          qs (vec quotes)]
      [:div {:style {:margin "0.4em 0"}}
       [:div {:style {:font-size "0.8em"
                      :color "#555"
                      :margin-bottom "0.2em"}}
        (i18n/t lang :quote/heading)]
       (into [:div]
             (for [[i q] (map-indexed vector qs)]
               ^{:key (str (:name q) "-" i)}
               [:div {:style {:display "flex"
                              :align-items "center"
                              :gap "0.5em"
                              :margin-bottom "0.25em"}}
                [:span {:style {:flex "1 1 auto"
                                :font-size "0.88em"}}
                 "❝ "
                 (:title q)
                 (when (:author-name q)
                   [:span {:style {:color "#8a7a55"}}
                    (str " — " (:author-name q))])]
                [:button {:title (i18n/t lang :ref/remove)
                          :on-click #(set-quotes! (into (subvec qs 0 i) (subvec qs (inc i))))
                          :style {:border "none"
                                  :background "transparent"
                                  :color "#c92a2a"
                                  :cursor "pointer"}}
                 "✕"]]))])))

;; --- read-only display on the page ------------------------------------------
(defn source-view
  "Render a document's one resolved source under the card (author → profile link,
  title, year, editor, locator, and a link when the work has a URL). Nothing when none."
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
        ;; the work's URL as a web icon at the start of the line, linking to the address
        (when-not (str/blank? (:url src))
          [:a {:href (:url src)
               :target "_blank"
               :rel "noopener noreferrer"
               :title (:url src)
               :style {:text-decoration "none"
                       :margin-right "0.4em"}}
           "🌐"])
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

(defn quotes-view
  "Read display of the source-KIs a document **quotes** — each links to the quotation (a
  `kind=source` KI) and shows its work author + locator. Nothing when none."
  [quotes]
  (when (seq quotes)
    (let [lang @(rf/subscribe [::i18n/lang])]
      [:div {:style {:margin-top "1.1em"}}
       [:div {:style {:font-weight 700
                      :color "#8a7a55"
                      :font-size "0.82em"
                      :text-transform "uppercase"
                      :letter-spacing "0.04em"
                      :margin-bottom "0.35em"}}
        (i18n/t lang :quote/heading)]
       (into [:ul {:style {:margin 0
                           :padding-left "1.2em"
                           :color "#555"
                           :font-size "0.9em"
                           :line-height "1.5"}}]
             (for [q quotes]
               ^{:key (str (:name q) "-" (:major q))}
               [:li
                "❝ "
                [:a {:href (i18n/ki lang q)
                     :style {:color "#b9770e"
                             :text-decoration "none"
                             :font-weight 600}}
                 (:title q)]
                (when-not (str/blank? (:author-name q)) (str " — " (:author-name q)))
                (when-not (str/blank? (:locator q))
                  [:span {:style {:color "#888"}}
                   (str " (" (:locator q) ")")])]))])))
