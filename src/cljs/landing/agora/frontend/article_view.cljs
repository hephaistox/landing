(ns landing.agora.frontend.article-view
  "Presentational component for a single article (#31): title, publication
  timestamp and body. The body is plain text; blank lines separate paragraphs.

  The body may embed **KI citations** — a `[[ki:<name>@<major>]]` token (optionally
  `[[ki:<name>@<major>|custom text]]`). Each renders as a special inline link (an
  `<a>` that knows it points at a Knowledge Item): its text defaults to the KI's
  title, it is visually marked as a KI, and hovering shows a card of the KI's
  metadata (kind, version, language, date, author). Citations are *living* — a
  `name + major` reference resolving to the latest minor via the by-major endpoint,
  so an article stays current as the KI is refined."
  (:require
   [clojure.string                 :as str]
   [landing.agora.domain           :as domain]
   [landing.agora.frontend.auth    :as auth]
   [landing.agora.frontend.fmt     :as fmt]
   [landing.agora.frontend.i18n    :as i18n]
   [landing.agora.frontend.ki-view :as ki-view]
   [landing.agora.frontend.ui      :as ui]
   [re-frame.core                  :as rf]
   [reagent.core                   :as r]))

;; ---------------------------------------------------------------------------
;; KI citation token parsing
;; ---------------------------------------------------------------------------

(defn- humanize
  "A readable heading from a slug: `confidence-is-partial` → `Confidence is
  partial`. Used as the citation label until the KI's real title has loaded."
  [s]
  (let [t (-> (or s "")
              (str/replace #"[-_]+" " ")
              str/trim)]
    (if (str/blank? t) (str s) (str (str/upper-case (subs t 0 1)) (subs t 1)))))

(defn parse-segments
  "Split a paragraph into a vector of parts: plain strings and citation maps
  `{:name … :major … :text …}` (`:text` nil unless the token gave a custom label)."
  [s]
  ;; one grammar source — the cljc `domain/cite-pattern` — with the global flag for
  ;; the positional scan below.
  (let [re (js/RegExp. (.-source domain/cite-pattern) "g")]
    (loop [pos 0
           out []]
      (if-let [m (.exec re s)]
        (let [idx (.-index m)
              whole (aget m 0)
              pre (subs s pos idx)
              out (cond-> out
                    (seq pre) (conj pre))]
          (recur (+ idx (.-length whole))
                 (conj out
                       {:name (aget m 1)
                        :major (js/parseInt (aget m 2))
                        :text (aget m 3)})))
        (let [tail (subs s pos)]
          (cond-> out
            (seq tail) (conj tail)))))))

;; ---------------------------------------------------------------------------
;; KI citation link + hover card
;; ---------------------------------------------------------------------------

(defn- cite-link-style
  [kind]
  (let [c (get domain/kind-color kind "#b9770e")]
    {:color c
     :text-decoration "none"
     :border-bottom (str "1px dotted " c)
     :cursor "pointer"
     :font-weight 600}))

(defn- ki-hover-card
  "The metadata popover shown while a citation is hovered/focused."
  [doc]
  [:span {:style {:position "absolute"
                  :left 0
                  :top "1.7em"
                  :z-index 50
                  :display "block"
                  :width "24em"
                  :max-width "90vw"
                  :white-space "normal"
                  :background "#fff"
                  :color "#222"
                  :border "1px solid #e0cba8"
                  :border-radius "0.5em"
                  :box-shadow "0 6px 24px rgba(0,0,0,0.18)"
                  :padding "0.8em 0.9em"
                  :font-size "0.85rem"
                  :line-height "1.45"
                  :font-weight 400
                  :text-align "left"}}
   [:span {:style {:display "flex"
                   :align-items "baseline"
                   :gap "0.5em"
                   :margin-bottom "0.35em"}}
    [:span {:style {:font-weight 700
                    :font-size "1.05em"}}
     (or (:title doc) (humanize (:name doc)))]
    [ki-view/kind-badge (:kind doc)]]
   (when-let [s (:output-statement doc)]
     [:span {:style {:display "-webkit-box"
                     :-webkit-line-clamp 3
                     :-webkit-box-orient "vertical"
                     :overflow "hidden"
                     :color "#555"
                     :margin-bottom "0.5em"}}
      s])
   [:span {:style {:display "flex"
                   :flex-wrap "wrap"
                   :align-items "center"
                   :gap "0.45em"
                   :color "#888"
                   :font-size "0.9em"}}
    [:span (str "v" (:major doc) "." (:minor doc))]
    [ki-view/lang-badge (:lang doc)]
    (when-let [d (fmt/utc (:published-at doc))] [:span "· " d])
    (when-let [a (:author doc)] [:span "· " a])]])

(defn ki-cite
  "An inline living citation of a KI (`name + major`). Fetches the KI (by-major,
  latest minor) into the shared cache, renders its title as a KI-marked link, and
  shows `ki-hover-card` on hover/focus."
  [seg]
  (let [{:keys [name major]} seg]
    (rf/dispatch [:agora/ensure-ki-by-major name major @(rf/subscribe [::i18n/lang])]))
  (let [hover? (r/atom false)]
    (fn [{:keys [name major text]}]
      (let [lang @(rf/subscribe [::i18n/lang])
            doc @(rf/subscribe [:agora/cite-doc name major lang])]
        [:span {:style {:position "relative"}
                :on-mouse-enter #(reset! hover? true)
                :on-mouse-leave #(reset! hover? false)}
         [:a {:href (i18n/ki lang
                             {:name name
                              :major major})
              :style (cite-link-style (:kind doc))
              :title "Knowledge Item"
              :on-focus #(reset! hover? true)
              :on-blur #(reset! hover? false)}
          [:span {:style {:font-size "0.75em"
                          :margin-right "0.25em"
                          :vertical-align "0.08em"}}
           "◆"]
          (or text (:title doc) (humanize name))]
         (when (and @hover? doc) [ki-hover-card doc])]))))

(defn- paragraph
  "One body paragraph, rendered with any KI citations resolved to `ki-cite`s."
  [para]
  (into [:p {:style {:margin "0 0 1em"}}]
        (map (fn [seg] (if (string? seg) seg [ki-cite seg])) (parse-segments para))))

;; ---------------------------------------------------------------------------
;; Article
;; ---------------------------------------------------------------------------

(defn article-card
  "Render one article map (as returned by GET /api/article/:id)."
  [{:keys [title body published-at]}]
  [:article {:style {:width "44em"
                     :max-width "100%"
                     :box-sizing "border-box"
                     :margin "1em auto"
                     :padding "1.5em"
                     :font-family "system-ui, sans-serif"}}
   [:h1 {:style {:font-size "1.8em"
                 :line-height "1.2"
                 :margin "0 0 0.2em"}}
    title]
   [:div {:style {:color "#888"
                  :font-size "0.85em"
                  :margin-bottom "1.3em"}}
    (or (fmt/utc published-at) "—")]
   (into [:div {:style {:font-size "1.05em"
                        :line-height "1.65"
                        :color "#222"}}]
         (map-indexed (fn [i para] ^{:key i} [paragraph para])
                      (remove str/blank? (str/split (or body "") #"\n\n+"))))])

;; ---------------------------------------------------------------------------
;; Article discover
;; ---------------------------------------------------------------------------

(defn- article-preview-card
  [lang a]
  [:a {:href (i18n/article-permalink lang a)
       :style {:display "flex"
               :flex-direction "column"
               :gap "0.4em"
               :min-height "6em"
               :padding "0.9em 1em"
               :border "1px solid #e2ddd2"
               :border-radius "0.6em"
               :background "#fff"
               :text-decoration "none"
               :color "inherit"}}
   [:div {:style {:font-weight 700
                  :font-size "1.1em"
                  :line-height "1.25"
                  :color "#1b1a17"}}
    (:title a)]
   [:div {:style {:margin-top "auto"
                  :color "#888"
                  :font-size "0.8em"}}
    (or (fmt/utc (:published-at a)) "—")]])

(defn articles-discover
  "Public article list — a responsive grid of preview cards linking to permalinks,
  ending with a `+` card to author a new article."
  [articles]
  (let [lang @(rf/subscribe [::i18n/lang])]
    [:div {:style {:max-width "72em"
                   :margin "1.5em auto"
                   :padding "0 0.8em"
                   :font-family "system-ui, sans-serif"}}
     [:p {:style {:color "#666"
                  :margin "0 0 1em"}}
      (i18n/t lang :articles/tagline)]
     (into [:div {:style {:display "grid"
                          :grid-template-columns "repeat(auto-fill, minmax(min(17em, 100%), 1fr))"
                          :gap "0.9em"}}]
           (conj (mapv (fn [a] ^{:key (:id a)} [article-preview-card lang a]) articles)
                 ^{:key "__add__"}
                 [ki-view/add-card (i18n/new-article lang) (i18n/t lang :nav/new-article)]))]))

;; ---------------------------------------------------------------------------
;; Article authoring (writing page)
;; ---------------------------------------------------------------------------

(rf/reg-sub ::form (fn [db _] (::form db)))
(rf/reg-event-db ::form-set (fn [db [_ k v]] (assoc-in db [::form k] v)))

(rf/reg-event-db ::op-failed
                 (fn [db [_ resp]]
                   (js/console.error "[agora] article op failed:" (clj->js resp))
                   (update db ::form dissoc :submitting?)))

;; KI picker used to insert a citation into the body at the cursor.
(rf/reg-sub ::ki-search (fn [db _] (::ki-search db)))
(rf/reg-event-db ::ki-search-clear (fn [db _] (dissoc db ::ki-search)))
(rf/reg-event-fx ::ki-search-input
                 (fn [{:keys [db]} [_ q]]
                   (if (str/blank? q)
                     {:db (assoc db
                                 ::ki-search
                                 {:q q
                                  :results []})}
                     {:db (assoc-in db [::ki-search :q] q)
                      :fetch {:method :get
                              :url (str "/agora/api/ki?lang=" (i18n/current db)
                                        "&q=" (js/encodeURIComponent q))
                              :headers {"Accept" "application/json"}
                              :response-content-types {#"application/json" :json}
                              :on-success [::ki-search-ok]
                              :on-failure [::op-failed]}})))
(rf/reg-event-db ::ki-search-ok
                 (fn [db [_ resp]] (assoc-in db [::ki-search :results] (:body resp))))

(rf/reg-event-fx ::submit
                 (fn [{:keys [db]} _]
                   (let [{:keys [name title body]} (::form db)]
                     {:db (assoc-in db [::form :submitting?] true)
                      :fetch {:method :post
                              :url "/agora/api/article"
                              :headers {"Content-Type" "application/json"
                                        "Accept" "application/json"}
                              :body (js/JSON.stringify (clj->js {:name name
                                                                 :title title
                                                                 :lang (i18n/current db)
                                                                 :body body}))
                              :response-content-types {#"application/json" :json}
                              :on-success [::created]
                              :on-failure [::op-failed]}})))

(rf/reg-event-fx ::created
                 (fn [{:keys [db]} [_ resp]]
                   {:db (dissoc db ::form ::ki-search)
                    :agora/navigate (i18n/article-permalink (i18n/current db) (:body resp))}))

(defn- ki-insert-search
  "A KI search box; clicking a result calls `(insert! {:name :major})` to splice a
  `[[ki:name@major]]` citation into the body at the cursor."
  [insert!]
  (let [{:keys [q results]} @(rf/subscribe [::ki-search])
        lang @(rf/subscribe [::i18n/lang])]
    [:div {:style {:position "relative"
                   :margin "0.5em 0 0.2em"}}
     [:input {:type "text"
              :placeholder (i18n/t lang :article-form/ki-search-ph)
              :value (or q "")
              :on-change #(rf/dispatch [::ki-search-input (.. % -target -value)])
              :style {:width "100%"
                      :box-sizing "border-box"
                      :padding "0.5em"
                      :font-size "0.95em"
                      :border "1px solid #ccc"
                      :border-radius "0.3em"}}]
     (when (and (not (str/blank? q)) (seq results))
       (into [:div {:style {:position "absolute"
                            :z-index 20
                            :left 0
                            :right 0
                            :margin-top "0.2em"
                            :background "#fff"
                            :border "1px solid #ddd"
                            :border-radius "0.4em"
                            :box-shadow "0 4px 12px rgba(0,0,0,0.1)"
                            :max-height "18em"
                            :overflow-y "auto"}}]
             (for [k results]
               ^{:key (:id k)}
               [:button {:on-click (fn [] (insert! k) (rf/dispatch [::ki-search-clear]))
                         :style {:display "flex"
                                 :align-items "center"
                                 :gap "0.5em"
                                 :width "100%"
                                 :text-align "left"
                                 :padding "0.5em 0.7em"
                                 :border "none"
                                 :border-bottom "1px solid #f0f0f0"
                                 :background "#fff"
                                 :cursor "pointer"}}
                [ki-view/kind-badge (:kind k)]
                [:span {:style {:font-weight 600}}
                 (:name k)]
                [:span {:style {:color "#aaa"
                                :font-size "0.72em"
                                :font-family "monospace"}}
                 (str "v" (:major k) "." (:minor k))]])))]))

(defn article-new-form
  "Standalone article authoring page: name, title and a body textarea, with a KI
  search box that inserts a `[[ki:…]]` citation at the cursor. On publish it POSTs
  /agora/api/article and navigates to the new article's permalink."
  []
  (let [node (atom nil)
        fit! (fn []
               (when-let [el @node]
                 (set! (.. el -style -height) "auto")
                 (set! (.. el -style -height) (str (.-scrollHeight el) "px"))))
        insert! (fn [k]
                  (when-let [el @node]
                    (let [tag (str "[[ki:" (:name k) "@" (:major k) "]]")
                          v (or (.-value el) "")
                          s (.-selectionStart el)
                          e (.-selectionEnd el)
                          v' (str (subs v 0 s) tag (subs v e))]
                      (rf/dispatch-sync [::form-set :body v'])
                      (js/setTimeout (fn []
                                       (.focus el)
                                       (let [pos (+ s (count tag))] (.setSelectionRange el pos pos))
                                       (fit!))
                                     0))))]
    (fn []
      (let [{:keys [name title body submitting?]} @(rf/subscribe [::form])
            user @(rf/subscribe [::auth/user])
            lang @(rf/subscribe [::i18n/lang])
            label {:font-size "0.8em"
                   :color "#555"
                   :margin-bottom "0.3em"}
            field {:width "100%"
                   :box-sizing "border-box"
                   :padding "0.5em"
                   :font-family "inherit"
                   :font-size "0.95em"
                   :border "1px solid #ccc"
                   :border-radius "0.3em"
                   :margin-bottom "0.8em"}
            blank? (or (str/blank? name) (str/blank? title) (str/blank? body))]
        [:div {:style {:width "44em"
                       :max-width "100%"
                       :box-sizing "border-box"
                       :margin "1.5em auto"
                       :padding "1.5em"
                       :background "#fff"
                       :border "1px solid #e2ddd2"
                       :border-radius "0.6em"
                       :font-family "system-ui, sans-serif"}}
         [ui/on-escape #(rf/dispatch [:agora/goto (i18n/articles lang)])]
         [:h1 {:style {:font-size "1.3em"
                       :margin "0 0 0.8em"}}
          (i18n/t lang :article-form/new-title)]
         [:div {:style label}
          (i18n/t lang :article-form/name)]
         [:input {:type "text"
                  :placeholder (i18n/t lang :article-form/name-ph)
                  :value (or name "")
                  :on-change #(rf/dispatch [::form-set :name (.. % -target -value)])
                  :style field}]
         [:div {:style label}
          (i18n/t lang :article-form/title)]
         [:input {:type "text"
                  :placeholder (i18n/t lang :article-form/title-ph)
                  :value (or title "")
                  :on-change #(rf/dispatch [::form-set :title (.. % -target -value)])
                  :style field}]
         [:div {:style label}
          (i18n/t lang :article-form/body)]
         [:textarea {:ref #(reset! node %)
                     :placeholder (i18n/t lang :article-form/body-ph)
                     :value (or body "")
                     :on-change
                     (fn [e] (rf/dispatch [::form-set :body (.. e -target -value)]) (fit!))
                     :style {:width "100%"
                             :box-sizing "border-box"
                             :resize "none"
                             :overflow "hidden"
                             :min-height "10em"
                             :padding "0.6em"
                             :font-family "inherit"
                             :font-size "1.02em"
                             :line-height "1.55"
                             :border "1px solid #ccc"
                             :border-radius "0.3em"}}]
         [:div {:style {:font-size "0.8em"
                        :color "#888"
                        :margin "0.2em 0"}}
          (i18n/t lang :article-form/insert-ki)]
         [ki-insert-search insert!]
         [:div {:style {:display "flex"
                        :gap "0.5em"
                        :margin-top "1em"}}
          [:button {:on-click (cond
                                (not user) #(rf/dispatch [::auth/open :login])
                                (or blank? submitting?) nil
                                :else #(rf/dispatch [::submit]))
                    :disabled (boolean (and user (or blank? submitting?)))
                    :style {:padding "0.4em 0.9em"
                            :border "none"
                            :background "#b9770e"
                            :color "#fff"
                            :border-radius "0.3em"
                            :opacity (if user 1 0.7)
                            :cursor (if (and user (or blank? submitting?)) "default" "pointer")}}
           (cond
             (not user) (i18n/t lang :article-form/login-to-create)
             submitting? (i18n/t lang :article-form/creating)
             :else (i18n/t lang :article-form/create))]
          [:a {:href (i18n/articles lang)
               :style {:padding "0.4em 0.9em"
                       :border "1px solid #ccc"
                       :background "#fff"
                       :border-radius "0.3em"
                       :text-decoration "none"
                       :color "#444"}}
           (i18n/t lang :article-form/cancel)]]]))))
