(ns landing.agora.frontend.ki-view
  "The KI page: the focused Knowledge Item as an editable card, its input KIs as
  mini cards above (each removable, with an add-input control by the arrows) and
  its successor KIs as mini cards below, joined by directed connectors.

  Editing happens in place on the card (a pencil toggles the fields). Input links
  are managed where the inputs are shown: a drop control on each input link, and
  an add/search/create control next to the top connector. Edit and link
  operations POST to the API and fold the result back into the view via
  :agora/edited / :agora/ki-updated (registered in core).

  Not auth-gated yet — OAuth arrives in #38."
  (:require
   [clojure.string                        :as str]
   [landing.agora.frontend.auth           :as auth]
   [landing.agora.frontend.fmt            :as fmt]
   [landing.agora.frontend.i18n           :as i18n]
   [landing.agora.frontend.ki-edit-common :as common]
   [landing.language                      :as language]
   [re-frame.core                         :as rf]
   [reagent.core                          :as r]
   [superstructor.re-frame.fetch-fx]))

;; ===========================================================================
;; Types
;; ===========================================================================

(def ^:private type-bg
  "Accent colour per KI type. Labels are translated (see i18n `:type/…`)."
  {"derived" "#2c5aa0"
   "verifiable-claim" "#0b7285"
   "postulate" "#6741d9"
   "stance" "#b9770e"
   "belief" "#2b8a3e"
   "credo" "#c92a2a"})

(defn type-badge-view
  "Coloured type badge with a label localized to the current UI language."
  [ki-type]
  (let [lang @(rf/subscribe [::i18n/lang])]
    [:span {:style {:display "inline-block"
                    :background (get type-bg ki-type "#666")
                    :color "#fff"
                    :font-size "0.7em"
                    :font-weight 700
                    :letter-spacing "0.05em"
                    :text-transform "uppercase"
                    :padding "0.2em 0.6em"
                    :border-radius "0.25em"}}
     (i18n/t lang (keyword "type" ki-type))]))

(defn lang-badge
  "The content language of a KI, shown as a small outlined code (FR / EN)."
  [lang]
  (when lang
    [:span {:title (get language/language-name lang lang)
            :style {:display "inline-block"
                    :border "1px solid #b9770e"
                    :color "#b9770e"
                    :background "transparent"
                    :font-size "0.65em"
                    :font-weight 700
                    :letter-spacing "0.06em"
                    :text-transform "uppercase"
                    :padding "0.15em 0.45em"
                    :border-radius "0.25em"}}
     lang]))

(defn- humanize
  "Turn a slug name (`confidence-is-partial`) into a readable title
  (`Confidence is partial`). Falls back to the raw string if blank."
  [s]
  (let [t (-> (or s "")
              (str/replace #"[-_]+" " ")
              str/trim)]
    (if (str/blank? t) (or s "") (str (str/upper-case (subs t 0 1)) (subs t 1)))))

(defn display-title
  "What to show as a KI's heading: its per-language `title` when set, else a
  humanized form of the identity slug `name`."
  [title name]
  (if (str/blank? title) (humanize name) title))

(defn- byline
  "The KI's authorship line: 'author · date'. Author is the owning user's name
  (copper), omitted when the KI is unowned."
  [author published-at]
  [:div {:style {:color "#888"
                 :font-size "0.8em"
                 :margin-bottom "0.7em"}}
   (when author
     [:span
      [:span {:style {:color "#b9770e"
                      :font-weight 600}}
       author]
      " · "])
   (or (fmt/utc published-at) "—")])

(defn language-mismatch-notice
  "When the shown KI is in a different language than the interface AND a version in
  the interface language exists, a banner (below the card) points the reader to it
  — for cases where a link led to a specific-language version (e.g. an untranslated
  neighbour resolved to its own language)."
  [ui-lang
   {ki-lang :lang
    :keys [translations]}]
  (when-let [alt (and (not= ki-lang ui-lang) (first (filter #(= (:lang %) ui-lang) translations)))]
    [:div {:style {:width "40em"
                   :max-width "100%"
                   :box-sizing "border-box"
                   :margin "0.6em auto 0"
                   :padding "0.6em 0.9em"
                   :background "#fbf3e0"
                   :border "1px solid #e6c88a"
                   :border-radius "0.4em"
                   :font-size "0.85em"
                   :color "#7a5209"
                   :text-align "center"}}
     (str "🌐 "
          (i18n/t ui-lang :ki/lang-notice-shown)
          " "
          (get language/language-name ki-lang ki-lang)
          ". ")
     [:a {:href (i18n/ki ui-lang alt)
          :style {:color "#b9770e"
                  :font-weight 600
                  :text-decoration "none"}}
      (i18n/t ui-lang :ki/lang-notice-switch)]]))

(defn languages-control
  "The KI's language, as one control: the badge shows the selected language; a
  caret opens a Wikipedia-style modal listing every language. Existing versions
  are the current one (marked) and the others (links); missing ones appear with a
  `+` for a logged-in user to create that version (duplicating the KI and its
  inputs). With nothing to switch to or create, it is just the badge. `ui-lang` is
  the interface language; `ki` supplies this KI's id/lang/name/major and its
  `:translations`."
  [ui-lang
   {ki-lang :lang
    ki-name :name
    ki-title :title
    source-text :output-statement
    :keys [id major translations]}]
  (r/with-let
   [open? (r/atom false)]
   (let [user @(rf/subscribe [::auth/user])
         present (into {ki-lang {:lang ki-lang
                                 :name ki-name
                                 :major major
                                 :current? true}}
                       (map (juxt :lang identity) translations))
         missing (remove present language/languages)
         openable? (or (seq translations) (and user (seq missing)))]
     [:span
      [:button {:on-click (when openable? #(reset! open? true))
                :disabled (not openable?)
                :title (when openable? (i18n/t ui-lang :ki/other-languages))
                :style {:display "inline-flex"
                        :align-items "center"
                        :gap "0.25em"
                        :border "1px solid #b9770e"
                        :color "#b9770e"
                        :background "transparent"
                        :font-size "0.65em"
                        :font-weight 700
                        :letter-spacing "0.06em"
                        :text-transform "uppercase"
                        :padding "0.15em 0.45em"
                        :border-radius "0.25em"
                        :cursor (if openable? "pointer" "default")}}
       ki-lang
       (when openable?
         [:span {:style {:font-size "0.85em"}}
          "▾"])]
      (when @open?
        [:div {:on-click #(reset! open? false)
               :style {:position "fixed"
                       :inset 0
                       :z-index 100
                       :background "rgba(0,0,0,0.45)"
                       :display "flex"
                       :align-items "flex-start"
                       :justify-content "center"
                       :padding-top "12vh"}}
         [:div {:on-click #(.stopPropagation %)
                :style {:width "18em"
                        :max-width "90%"
                        :background "#fff"
                        :border-radius "0.6em"
                        :padding "1.1em 1.3em"
                        :font-family "system-ui, sans-serif"}}
          [:h3 {:style {:margin "0 0 0.7em"
                        :font-size "1.05em"}}
           (str "🌐 " (i18n/t ui-lang :ki/other-languages))]
          (into
           [:div {:style {:display "flex"
                          :flex-direction "column"
                          :gap "0.15em"}}]
           (for [l language/languages
                 :let [entry (present l)]
                 ;; hide missing languages from anonymous users (they cannot
                 ;; create), so they only see the switchable ones.
                 :when (or entry user)]
             ^{:key l}
             (cond
               (:current? entry) [:div {:style {:padding "0.4em 0.5em"
                                                :border-radius "0.3em"
                                                :background "#f4efe4"
                                                :font-weight 700
                                                :color "#7a5209"}}
                                  (get language/language-name l l)]
               entry [:a {:href (i18n/ki l entry)
                          :on-click #(reset! open? false)
                          :style {:padding "0.4em 0.5em"
                                  :border-radius "0.3em"
                                  :text-decoration "none"
                                  :color "#b9770e"}}
                      (get language/language-name l l)]
               :else [:button {:on-click (fn []
                                           (reset! open? false)
                                           (rf/dispatch [::translate-open
                                                         {:ki-id id
                                                          :source-lang ki-lang
                                                          :target-lang l
                                                          :source-text source-text
                                                          :source-title (display-title ki-title
                                                                                       ki-name)}]))
                               :title (i18n/t ui-lang :ki/create-translation)
                               :style {:text-align "left"
                                       :padding "0.4em 0.5em"
                                       :border "1px dashed #b9770e"
                                       :border-radius "0.3em"
                                       :background "transparent"
                                       :color "#b9770e"
                                       :cursor "pointer"
                                       :font-size "0.95em"}}
                      (str "+ " (get language/language-name l l))])))]])])))

(defn type-selector
  "All KI types as clickable badges; the selected one is highlighted, the others
  dimmed. Calls `on-select` with the chosen type string."
  [selected on-select]
  (into [:div {:style {:display "flex"
                       :flex-wrap "wrap"
                       :gap "0.4em"}}]
        ;; `common/ki-types` is the canonical set as keywords; the DB/API represent
        ;; the type as a string, so map to `(name kw)` at this boundary.
        (for [t (map name common/ki-types)
              :let [current? (= t selected)]]
          ^{:key t}
          [:button {:on-click #(on-select t)
                    :title t
                    :style {:border "none"
                            :background "transparent"
                            :padding "0.1em"
                            :cursor "pointer"
                            :border-radius "0.3em"
                            :opacity (if current? 1 0.35)
                            :box-shadow (if current? "0 0 0 2px #333" "none")}}
           [type-badge-view t]])))

;; ===========================================================================
;; State + operations (edit + links)
;; ===========================================================================

(rf/reg-sub ::edit (fn [db _] (::edit db)))
(rf/reg-sub ::links (fn [db _] (::links db)))

(defn close-panels
  "Collapse the edit form and any open add-input panel. Used by core on route
  changes so nothing lingers from a previous KI."
  [db]
  (-> db
      (update ::edit assoc :open? false)
      (assoc ::links
             {:adding? false
              :creating? false
              :q ""
              :results []})))

(defn- json-req
  [method url body on-success]
  {:method method
   :url url
   :headers {"Content-Type" "application/json"
             "Accept" "application/json"}
   :body (js/JSON.stringify (clj->js body))
   :response-content-types {#"application/json" :json}
   :on-success on-success
   :on-failure [::op-failed]})

(rf/reg-event-db ::op-failed
                 (fn [db [_ resp]]
                   (js/console.error "[agora] operation failed:" (clj->js resp))
                   (update db ::edit assoc :saving? false :error resp)))

;; ---- Edit (new minor version) ----

(rf/reg-event-db ::edit-open
                 (fn [db [_ ki]]
                   (assoc db
                          ::edit
                          {:open? true
                           :title (:title ki)
                           :type (:type ki)
                           :output-statement (:output-statement ki)
                           :saving? false
                           :error nil})))

(rf/reg-event-db ::edit-close (fn [db _] (update db ::edit assoc :open? false)))
(rf/reg-event-db ::edit-set (fn [db [_ k v]] (update db ::edit assoc k v)))

(rf/reg-event-fx ::edit-save
                 (fn [{:keys [db]} [_ ki-id]]
                   (let [{:keys [title type output-statement]} (::edit db)]
                     {:db (update db ::edit assoc :saving? true :error nil)
                      :fetch (json-req :post
                                       (str "/agora/api/ki/" ki-id "/edit")
                                       {:title title
                                        :type type
                                        :output-statement output-statement}
                                       [::edit-save-ok])})))

(rf/reg-event-fx ::edit-save-ok
                 (fn [{:keys [db]} [_ resp]]
                   (let [ki (:body resp)]
                     (if (:id ki)
                       {:dispatch [:agora/edited ki]}
                       {:db (update db ::edit assoc :saving? false :error resp)}))))

;; ---- Translate: duplicate a KI (and its inputs) into another language ----
;;
;; Opening the editor fetches a machine-translation suggestion; the author edits
;; it against the read-only source, then saves — which creates the new-language
;; KI (with the validated text) and its inputs, and lands on it.

(rf/reg-sub ::translate-form (fn [db _] (::translate-form db)))

(rf/reg-event-fx
 ::translate-open
 (fn [{:keys [db]} [_ {:keys [ki-id source-lang target-lang source-text source-title]}]]
   {:db (assoc db
               ::translate-form
               {:ki-id ki-id
                :source-lang source-lang
                :target-lang target-lang
                :source-text source-text
                :translation source-text
                :source-title source-title
                :title source-title
                :suggesting? true
                :title-suggesting? true
                :saving? false})
    ;; suggest a translation for both the title and the statement
    :dispatch-n [[::translate-suggest :title source-title source-lang target-lang]
                 [::translate-suggest :translation source-text source-lang target-lang]]}))

(rf/reg-event-fx ::translate-suggest
                 (fn [_ [_ field text source target]]
                   {:fetch {:method :post
                            :url "/agora/api/translate"
                            :headers {"Content-Type" "application/json"
                                      "Accept" "application/json"}
                            :body (js/JSON.stringify (clj->js {:text text
                                                               :source source
                                                               :target target}))
                            :response-content-types {#"application/json" :json}
                            :on-success [::translate-suggest-ok field]
                            :on-failure [::translate-suggest-ok field]}}))

(rf/reg-event-db
 ::translate-suggest-ok
 (fn [db [_ field resp]]
   (if (::translate-form db)
     (update db
             ::translate-form
             (fn [f]
               (let [fallback (if (= field :title) (:source-title f) (:source-text f))
                     flag (if (= field :title) :title-suggesting? :suggesting?)]
                 (assoc f flag false field (or (get-in resp [:body :translation]) fallback)))))
     db)))

(rf/reg-event-db ::translate-set (fn [db [_ k v]] (assoc-in db [::translate-form k] v)))
(rf/reg-event-db ::translate-cancel (fn [db _] (dissoc db ::translate-form)))

(rf/reg-event-fx ::translate-save
                 (fn [{:keys [db]} _]
                   (let [{:keys [ki-id target-lang title translation]} (::translate-form db)]
                     {:db (assoc-in db [::translate-form :saving?] true)
                      :fetch (json-req :post
                                       (str "/agora/api/ki/" ki-id "/translate")
                                       {:lang target-lang
                                        :title title
                                        :statement translation}
                                       [::translate-saved target-lang])})))

(rf/reg-event-fx ::translate-saved
                 (fn [{:keys [db]} [_ target-lang resp]]
                   (let [ki (:body resp)]
                     (if (:id ki)
                       {:db (dissoc db ::translate-form)
                        :dispatch [:agora/goto (i18n/ki-id target-lang (:id ki))]}
                       {:db (assoc-in db [::translate-form :saving?] false)}))))

;; ---- Input links (add / drop / search / create) ----

(rf/reg-event-db ::links-open-add
                 (fn [db _]
                   (assoc db
                          ::links
                          {:adding? true
                           :creating? false
                           :q ""
                           :results []})))

(rf/reg-event-db ::links-reset
                 (fn [db _]
                   (assoc db
                          ::links
                          {:adding? false
                           :creating? false
                           :q ""
                           :results []})))

(rf/reg-event-db ::links-open-create
                 (fn [db _]
                   (update db
                           ::links
                           merge
                           {:creating? true
                            :new-name ""
                            :new-type "derived"
                            :new-statement ""})))

(rf/reg-event-db ::links-set (fn [db [_ k v]] (update db ::links assoc k v)))

(rf/reg-event-fx ::links-search
                 (fn [{:keys [db]} [_ q]]
                   (cond-> {:db (assoc-in db [::links :q] q)}
                     (not (str/blank? q)) (assoc :fetch
                                                 {:method :get
                                                  :url (str "/agora/api/ki?lang=" (i18n/current db)
                                                            "&q=" (js/encodeURIComponent q))
                                                  :headers {"Accept" "application/json"}
                                                  :response-content-types {#"application/json"
                                                                           :json}
                                                  :on-success [::links-search-ok]
                                                  :on-failure [::op-failed]}))))

(rf/reg-event-db ::links-search-ok (fn [db [_ resp]] (assoc-in db [::links :results] (:body resp))))

(rf/reg-event-fx ::add-input
                 (fn [_ [_ ki-id ref]]
                   {:fetch
                    (json-req :post (str "/agora/api/ki/" ki-id "/inputs") ref [::input-changed])}))

(rf/reg-event-fx
 ::drop-input
 (fn [_ [_ ki-id ref]]
   {:fetch (json-req :delete (str "/agora/api/ki/" ki-id "/inputs") ref [::input-changed])}))

(rf/reg-event-fx ::create-and-add
                 (fn [{:keys [db]} [_ ki-id]]
                   (let [{:keys [new-name new-type new-statement]} (::links db)]
                     {:fetch (json-req :post
                                       "/agora/api/ki"
                                       {:name new-name
                                        :type new-type
                                        :lang (i18n/current db)
                                        :output-statement new-statement}
                                       [::created ki-id])})))

(rf/reg-event-fx ::created
                 (fn [_ [_ ki-id resp]]
                   {:dispatch [::add-input ki-id (select-keys (:body resp) [:name :major])]}))

(rf/reg-event-fx ::input-changed
                 (fn [{:keys [db]} [_ resp]]
                   {:db (assoc db
                               ::links
                               {:adding? false
                                :creating? false
                                :q ""
                                :results []})
                    :dispatch [:agora/ki-updated (:body resp)]}))

;; ---- Create a new KI (standalone form, #34) ----

(rf/reg-sub ::new (fn [db _] (::new db)))
(rf/reg-event-db ::new-set (fn [db [_ k v]] (assoc-in db [::new k] v)))

(rf/reg-event-fx ::new-submit
                 (fn [{:keys [db]} _]
                   (let [{:keys [name title type lang output-statement]} (::new db)]
                     {:db (assoc-in db [::new :submitting?] true)
                      :fetch (json-req :post
                                       "/agora/api/ki"
                                       {:name name
                                        :title title
                                        :type (or type "derived")
                                        :lang (or lang (i18n/current db))
                                        :output-statement output-statement}
                                       [::new-created])})))

(rf/reg-event-fx ::new-created
                 (fn [{:keys [db]} [_ resp]]
                   ;; :agora/edited caches the new KI and navigates to its page (where inputs can
                   ;; then be added). Clear the form.
                   {:db (dissoc db ::new)
                    :dispatch [:agora/edited (:body resp)]}))

;; ===========================================================================
;; Components
;; ===========================================================================

(def ^:private version-tag-style
  {:color "#aaa"
   :font-size "0.72em"
   :font-family "monospace"})

(defn permalink
  "The public permanent URL of a KI ref in language `lang`:
  /agora/{lang}/ki/{name}/{major}."
  [lang k]
  (i18n/ki lang k))

;; ---- Full-text search box (#37) — searches name + statement, links to pages ----

(rf/reg-sub ::search (fn [db _] (::search db)))
(rf/reg-event-db ::search-clear (fn [db _] (dissoc db ::search)))

(rf/reg-event-fx ::search-input
                 (fn [{:keys [db]} [_ q]]
                   (if (str/blank? q)
                     {:db (assoc db
                                 ::search
                                 {:q q
                                  :results []})}
                     {:db (assoc-in db [::search :q] q)
                      :fetch {:method :get
                              :url (str "/agora/api/ki?lang=" (i18n/current db)
                                        "&q=" (js/encodeURIComponent q))
                              :headers {"Accept" "application/json"}
                              :response-content-types {#"application/json" :json}
                              :on-success [::search-ok]
                              :on-failure [::op-failed]}})))

(rf/reg-event-db ::search-ok (fn [db [_ resp]] (assoc-in db [::search :results] (:body resp))))

(defn search-box
  "A search input that queries name + output statement and shows matches as a
  dropdown of links to public KI pages."
  []
  (let [{:keys [q results]} @(rf/subscribe [::search])
        lang @(rf/subscribe [::i18n/lang])]
    [:div {:style {:position "relative"
                   :width "100%"}}
     [:input {:type "text"
              :placeholder (i18n/t lang :search/placeholder)
              :value (or q "")
              :on-change #(rf/dispatch [::search-input (.. % -target -value)])
              :style {:width "100%"
                      :box-sizing "border-box"
                      :padding "0.55em"
                      :font-size "1em"
                      :border "1px solid #ccc"
                      :border-radius "0.4em"}}]
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
                            :max-height "20em"
                            :overflow-y "auto"}}]
             (for [k results]
               ^{:key (:id k)}
               [:a {:href (permalink lang k)
                    :on-click #(rf/dispatch [::search-clear])
                    :style {:display "flex"
                            :align-items "center"
                            :gap "0.5em"
                            :padding "0.5em 0.7em"
                            :text-decoration "none"
                            :color "inherit"
                            :border-bottom "1px solid #f0f0f0"}}
                [type-badge-view (:type k)]
                [:span {:style {:font-weight 600}}
                 (:name k)]
                [:span {:style version-tag-style}
                 (str "v" (:major k) "." (:minor k))]])))
     (when (and (not (str/blank? q)) (empty? results))
       [:div {:style {:position "absolute"
                      :z-index 20
                      :left 0
                      :right 0
                      :margin-top "0.2em"
                      :background "#fff"
                      :border "1px solid #ddd"
                      :border-radius "0.4em"
                      :padding "0.5em 0.7em"
                      :color "#aaa"
                      :font-size "0.9em"}}
        (i18n/t lang :search/no-matches)])]))

(defn- gated
  "For an authoring action, returns [on-click dim?]. Logged in → runs `action`;
  logged out → opens the login modal and the caller shadows the control."
  [action]
  (if @(rf/subscribe [::auth/user])
    [action false]
    [(fn [e]
       (some-> e
               .preventDefault)
       (rf/dispatch [::auth/open :login]))
     true]))

(defn header
  "Shared Agora header (Hephaistox dark/copper theme). The `New KI` authoring link
  is shadowed and routes to login when logged out. There is no explicit Lab entry:
  a signed-in visitor gets the editable page automatically when viewing a KI. The
  language is a preference, set on the Preferences page — not here."
  []
  (let [lang @(rf/subscribe [::i18n/lang])
        user @(rf/subscribe [::auth/user])]
    [:header {:class "agora-header"
              :style {:background "#1b1a17"
                      :color "#e8e2d6"
                      :border-bottom "2px solid #b9770e"}}
     [:a {:href (i18n/discover lang)
          :style {:font-family "Georgia, 'Cormorant Garamond', serif"
                  :font-size "1.4em"
                  :font-weight 700
                  :letter-spacing "0.03em"
                  :color "#d99a2b"
                  :text-decoration "none"}}
      "Agora"]
     (into [:nav {:style {:display "flex"
                          :gap "1em"
                          :font-size "0.9em"}}]
           (for [[label href auth?] [[(i18n/t lang :nav/new-ki) (i18n/new-ki lang) true]]
                 :let [gate? (and auth? (not user))]]
             ^{:key href}
             [:a {:href (if gate? "#" href)
                  :on-click (when gate?
                              (fn [e] (.preventDefault e) (rf/dispatch [::auth/open :login])))
                  :style {:color "#e8e2d6"
                          :text-decoration "none"
                          :opacity (if gate? 0.4 0.85)}}
              label]))
     [:div {:class "agora-header__search"}
      [search-box]]
     [:div {:class "agora-header__auth"}
      [auth/auth-controls]]]))

(def ^:private footer-legal
  "Legal / info links, adapted from the hephaistox.com landing footer. Paths are
  under the language root of the main site (outside Agora)."
  [[:footer/home "index.html"]
   [:footer/legal-notice "articles/legal-notice.html"]
   [:footer/privacy "articles/privacy.html"]
   [:footer/disclaimer "articles/disclaimer.html"]
   [:footer/who-are-we "articles/who-are-we.html"]])

(def ^:private footer-social
  "Social links as FontAwesome brand icons, mirroring the hephaistox.com footer."
  [["LinkedIn" "fa-linkedin" "https://www.linkedin.com/company/hephaistox"]
   ["Facebook" "fa-facebook-f" "https://www.facebook.com/profile.php?id=61586135248424"]
   ["YouTube" "fa-youtube" "https://www.youtube.com/@HephaistoxSC"]
   ["GitHub" "fa-github" "https://github.com/hephaistox"]])

(defn site-footer
  "Agora footer, adapted from the hephaistox.com landing footer: legal/info links
  (to the main site, language-rooted), social icons, and copyright. Themed to
  match the header (dark/copper)."
  []
  (let [lang @(rf/subscribe [::i18n/lang])
        link {:color "#d9b38c"
              :text-decoration "none"
              :font-size "0.85em"}
        row {:display "flex"
             :flex-wrap "wrap"
             :justify-content "center"
             :gap "0.4em 1.2em"
             :margin-bottom "0.9em"}]
    [:footer {:style {:flex-shrink 0
                      :padding "1.6em 1.2em"
                      :background "#1b1a17"
                      :color "#e8e2d6"
                      :border-top "2px solid #b9770e"
                      :text-align "center"
                      :font-family "system-ui, sans-serif"}}
     (into [:div {:style row}]
           (concat [^{:key "prefs"}
                    [:a {:href (i18n/preferences lang)
                         :style link}
                     (i18n/t lang :nav/preferences)]]
                   (for [[k path] footer-legal]
                     ^{:key path}
                     [:a {:href (str "/" lang "/" path)
                          :style link}
                      (i18n/t lang k)])))
     (into [:div {:style (assoc row :gap "0.2em 1.4em" :font-size "1.35em")}]
           (for [[label icon url] footer-social]
             ^{:key label}
             [:a {:href url
                  :target "_blank"
                  :rel "noopener noreferrer"
                  :title label
                  :aria-label label
                  :style {:color "#d9b38c"
                          :text-decoration "none"
                          :line-height 1
                          :padding "0.15em"}}
              [:i {:class (str "fa-brands " icon)}]]))
     [:div {:style {:font-size "0.8em"
                    :color "#8a8377"}}
      "Hephaistox © 2026"]]))

(defn version-picker
  "Current version; clicking reveals an in-order strip of every version."
  [{:keys [major minor versions]}]
  (r/with-let
   [open? (r/atom false)]
   (let [lang @(rf/subscribe [::i18n/lang])]
     [:span {:style {:display "inline-flex"
                     :align-items "center"
                     :gap "0.4em"
                     :font-family "monospace"
                     :font-size "0.8em"}}
      [:button {:on-click #(swap! open? not)
                :title (i18n/t lang :ki/versions)
                :style {:font-family "inherit"
                        :font-size "inherit"
                        :color "#888"
                        :background "transparent"
                        :border "1px solid #ddd"
                        :border-radius "0.3em"
                        :padding "0.1em 0.5em"
                        :cursor "pointer"}}
       (str "v" major "." minor " " (if @open? "▴" "▾"))]
      (when @open?
        (into [:span {:style {:display "inline-flex"
                              :gap "0.3em"
                              :max-width "22em"
                              :overflow-x "auto"
                              :padding "0.1em"}}]
              (for [v (sort-by :minor versions)
                    :let [current? (= (:minor v) minor)]]
                ^{:key (:id v)}
                [:a {:href (i18n/ki-id lang (:id v))
                     :on-click #(reset! open? false)
                     :style {:flex "0 0 auto"
                             :text-decoration "none"
                             :padding "0.1em 0.5em"
                             :border-radius "0.3em"
                             :border (str "1px solid " (if current? "#b9770e" "#ddd"))
                             :background (if current? "#b9770e" "#fff")
                             :color (if current? "#fff" "#b9770e")}}
                 (str "v" major "." (:minor v))])))])))

(defn- mini-card
  "A compact neighbour card linking to `link`. When `on-drop` is given, a ✕
  removes the link (used for input links when editing)."
  [{c-name :name
    c-type :type
    :keys [major minor]}
   link
   on-drop]
  [:div {:style {:position "relative"
                 :width "16em"
                 :max-width "100%"}}
   [:a {:href link
        :style {:display "block"
                :box-sizing "border-box"
                :text-decoration "none"
                :color "inherit"
                :padding "0.55em 0.7em"
                :border "1px solid #ddd"
                :border-radius "0.4em"
                :background "#fff"}}
    [:div {:style {:display "flex"
                   :align-items "center"
                   :gap "0.5em"
                   :margin-bottom "0.3em"}}
     [type-badge-view c-type]
     [:span {:style version-tag-style}
      (str "v" major "." minor)]]
    [:div {:style {:font-weight 600
                   :font-size "0.9em"}}
     c-name]]
   (when on-drop
     [:button {:on-click on-drop
               :title (i18n/t @(rf/subscribe [::i18n/lang]) :ki/remove-input)
               :style {:position "absolute"
                       :top "0.2em"
                       :right "0.3em"
                       :border "none"
                       :background "transparent"
                       :color "#c92a2a"
                       :cursor "pointer"
                       :font-size "0.85em"
                       :line-height 1}}
      "✕"])])

(defn- connector
  "A short vertical directed link (arrow down = direction of implication)."
  []
  [:div {:style {:display "flex"
                 :flex-direction "column"
                 :align-items "center"
                 :margin "0.15em 0"}}
   [:div {:style {:width "2px"
                  :height "1.2em"
                  :background "#d9b38c"}}]
   [:div {:style {:color "#b9770e"
                  :font-size "0.95em"
                  :line-height "1"}}
    "▼"]])

(defn auto-textarea
  "A textarea that grows to fit its content — never too small, and never taller/wider
  than needed. Width is 100% of its card and manual resize is disabled, so it can't
  spill outside. `attrs` (`:value`/`:on-change`/`:placeholder`/`:disabled`/`:style`…)
  is merged onto the element; the caller's `:style` overrides the defaults."
  [_attrs]
  (let [node (atom nil)
        fit! (fn []
               (when-let [el @node]
                 (set! (.. el -style -height) "auto")
                 (set! (.. el -style -height) (str (.-scrollHeight el) "px"))))]
    (r/create-class
     {:display-name "auto-textarea"
      :component-did-mount fit!
      :component-did-update fit!
      :reagent-render (fn [{:keys [on-change style]
                            :as attrs}]
                        [:textarea
                         (merge {:ref #(reset! node %)}
                                (dissoc attrs :on-change :style)
                                {:on-change (fn [e] (when on-change (on-change e)) (fit!))
                                 :style (merge {:width "100%"
                                                :box-sizing "border-box"
                                                :resize "none"
                                                :overflow "hidden"
                                                :min-height "4.5em"
                                                :padding "0.5em"
                                                :font-family "inherit"
                                                :font-size "1.02em"
                                                :line-height "1.5"
                                                :border "1px solid #ccc"
                                                :border-radius "0.3em"}
                                               style)})])})))

(defn- create-input-form
  [ki-id {:keys [new-name new-type new-statement]}]
  (let [lang @(rf/subscribe [::i18n/lang])
        field {:width "100%"
               :box-sizing "border-box"
               :padding "0.4em"
               :font-family "inherit"
               :font-size "0.9em"
               :border "1px solid #ccc"
               :border-radius "0.3em"
               :margin-bottom "0.5em"}]
    [:div {:style {:margin-top "0.5em"
                   :padding "0.7em"
                   :border "1px solid #ddd"
                   :border-radius "0.4em"
                   :background "#fff"
                   :text-align "left"}}
     [:div {:style {:font-size "0.75em"
                    :color "#888"
                    :margin-bottom "0.4em"}}
      (i18n/t lang :ki/new-input)]
     [:input {:type "text"
              :placeholder (i18n/t lang :form/name)
              :value new-name
              :on-change #(rf/dispatch [::links-set :new-name (.. % -target -value)])
              :style field}]
     [:div {:style {:margin-bottom "0.5em"}}
      [type-selector new-type #(rf/dispatch [::links-set :new-type %])]]
     [auto-textarea {:placeholder (i18n/t lang :form/statement)
                     :value new-statement
                     :on-change #(rf/dispatch [::links-set :new-statement (.. % -target -value)])
                     :style (assoc field :min-height "3.2em")}]
     [:button {:on-click #(rf/dispatch [::create-and-add ki-id])
               :disabled (or (str/blank? new-name) (str/blank? new-statement))
               :style {:padding "0.35em 0.8em"
                       :border "none"
                       :background "#b9770e"
                       :color "#fff"
                       :border-radius "0.3em"
                       :cursor "pointer"}}
      (i18n/t lang :ki/create-and-add)]]))

(defn- add-input-control
  "The add-input affordance, sitting by the top connector. Search results exclude
  the KI itself (no self-loop) and any KI already an input."
  [ki]
  (let [ui @(rf/subscribe [::links])
        lang @(rf/subscribe [::i18n/lang])
        ki-id (:id ki)
        excluded (into #{[(:name ki) (:major ki)]} (map (juxt :name :major) (:inputs ki)))
        results (remove #(excluded [(:name %) (:major %)]) (:results ui))]
    (if-not (:adding? ui)
      (let [[on-click dim?] (gated #(rf/dispatch [::links-open-add]))]
        [:button {:on-click on-click
                  :title (when dim? (i18n/t lang :ki/login-to-add))
                  :style {:font-size "0.8em"
                          :background "transparent"
                          :border "1px dashed #b9770e"
                          :color "#b9770e"
                          :border-radius "0.3em"
                          :padding "0.25em 0.7em"
                          :cursor "pointer"
                          :opacity (if dim? 0.4 1)}}
         "+"])
      [:div {:style {:width "22em"
                     :max-width "100%"
                     :text-align "center"}}
       [:div {:style {:display "flex"
                      :justify-content "flex-end"}}
        [:button {:on-click #(rf/dispatch [::links-reset])
                  :title "Close"
                  :style {:border "none"
                          :background "transparent"
                          :color "#999"
                          :cursor "pointer"
                          :font-size "1.1em"
                          :line-height 1
                          :padding "0 0.1em"}}
         "×"]]
       [:input {:type "text"
                :placeholder (i18n/t lang :ki/search-input)
                :value (:q ui)
                :on-change #(rf/dispatch [::links-search (.. % -target -value)])
                :style {:width "100%"
                        :box-sizing "border-box"
                        :padding "0.45em"
                        :font-family "inherit"
                        :font-size "0.9em"
                        :border "1px solid #ccc"
                        :border-radius "0.3em"}}]
       (when (seq results)
         (into [:div {:style {:display "flex"
                              :flex-wrap "wrap"
                              :gap "0.3em"
                              :justify-content "center"
                              :margin-top "0.5em"}}]
               (for [r results]
                 ^{:key (:id r)}
                 [:button {:on-click #(rf/dispatch
                                       [::add-input ki-id (select-keys r [:name :major])])
                           :title (i18n/t lang :ki/add-input)
                           :style {:cursor "pointer"
                                   :border "1px solid #e3c48f"
                                   :background "#fff"
                                   :border-radius "0.3em"
                                   :padding "0.25em 0.5em"}}
                  [:span {:style {:display "inline-flex"
                                  :align-items "center"
                                  :gap "0.4em"
                                  :font-size "0.85em"}}
                   [type-badge-view (:type r)]
                   [:span (:name r)]
                   [:span {:style version-tag-style}
                    (str "v" (:major r) "." (:minor r))]]])))
       (when (and (not (str/blank? (:q ui))) (empty? results))
         [:div {:style {:color "#aaa"
                        :font-size "0.85em"
                        :margin "0.4em 0"}}
          (i18n/t lang :search/no-matches)])
       (if (:creating? ui)
         [create-input-form ki-id ui]
         [:div {:style {:margin-top "0.4em"}}
          [:button {:on-click #(rf/dispatch [::links-open-create])
                    :style {:font-size "0.8em"
                            :background "transparent"
                            :border "1px dashed #b9770e"
                            :color "#b9770e"
                            :border-radius "0.3em"
                            :padding "0.25em 0.7em"
                            :cursor "pointer"}}
           (i18n/t lang :ki/create-new)]])])))

(def ^:private card-style
  {:position "relative"
   :width "40em"
   :max-width "100%"
   :box-sizing "border-box"
   :padding "1.25em 1.5em"
   :border "1px solid #ccc"
   :border-radius "0.5em"
   :background "#fff"
   :box-shadow "0 1px 3px rgba(0,0,0,0.06)"
   :font-family "system-ui, sans-serif"})

(defn- edit-card
  "The card in edit mode: type selector, editable title + statement, in place."
  [{ki-name :name
    ki-lang :lang
    :keys [id major minor published-at author]}
   {:keys [title type output-statement saving? error]}]
  (let [lang @(rf/subscribe [::i18n/lang])]
    [:article {:style card-style}
     [:div {:style {:display "flex"
                    :align-items "center"
                    :gap "0.75em"
                    :margin-bottom "0.6em"}}
      [type-selector type #(rf/dispatch [::edit-set :type %])]
      [lang-badge ki-lang]
      [:span {:style {:color "#888"
                      :font-size "0.8em"
                      :font-family "monospace"}}
       (str "v" major "." minor " " (i18n/t lang :form/next))]]
     [:input {:type "text"
              :value (or title "")
              :placeholder (display-title nil ki-name)
              :on-change #(rf/dispatch [::edit-set :title (.. % -target -value)])
              :style {:width "100%"
                      :box-sizing "border-box"
                      :margin "0.2em 0 0.1em"
                      :padding "0.35em 0.4em"
                      :font-size "1.3em"
                      :font-weight 700
                      :font-family "inherit"
                      :border "1px solid #eee"
                      :border-radius "0.3em"}}]
     [byline author published-at]
     [auto-textarea {:value output-statement
                     :on-change #(rf/dispatch
                                  [::edit-set :output-statement (.. % -target -value)])}]
     (when error
       [:div {:style {:color "#c92a2a"
                      :font-size "0.85em"
                      :margin-top "0.5em"}}
        (i18n/t lang :form/save-failed)])
     [:div {:style {:display "flex"
                    :gap "0.5em"
                    :margin-top "0.7em"}}
      [:button {:on-click #(rf/dispatch [::edit-save id])
                :disabled (boolean saving?)
                :style {:padding "0.4em 0.9em"
                        :border "none"
                        :background "#b9770e"
                        :color "#fff"
                        :border-radius "0.3em"
                        :cursor (if saving? "default" "pointer")}}
       (if saving? (i18n/t lang :form/saving) (i18n/t lang :form/save))]
      [:button {:on-click #(rf/dispatch [::edit-close])
                :style {:padding "0.4em 0.9em"
                        :border "1px solid #ccc"
                        :background "#fff"
                        :border-radius "0.3em"
                        :cursor "pointer"}}
       (i18n/t lang :form/cancel)]]]))

(defn- static-card
  "The card in read mode. When editable (`edit?` true) a pencil switches to in-place
  editing; on the public page it is omitted."
  [{ki-name :name
    ki-title :title
    ki-type :type
    :keys [major minor published-at output-statement versions author]
    :as ki}
   edit?]
  (let [lang @(rf/subscribe [::i18n/lang])]
    [:article {:style card-style}
     (when edit?
       (let [[on-click dim?] (gated #(rf/dispatch [::edit-open ki]))]
         [:button {:on-click on-click
                   :title (if dim? (i18n/t lang :ki/login-to-edit) (i18n/t lang :ki/edit))
                   :style {:position "absolute"
                           :top "0.7em"
                           :right "0.8em"
                           :border "1px solid #ddd"
                           :background "#fff"
                           :color "#b9770e"
                           :border-radius "0.3em"
                           :width "2em"
                           :height "2em"
                           :cursor "pointer"
                           :font-size "0.95em"
                           :line-height 1
                           :opacity (if dim? 0.4 1)}}
          "✎"]))
     [:div {:style {:display "flex"
                    :align-items "center"
                    :gap "0.75em"
                    :margin-bottom "0.5em"}}
      [type-badge-view ki-type]
      [languages-control lang ki]
      [version-picker {:major major
                       :minor minor
                       :versions versions}]]
     [:h1 {:style {:font-size "1.3em"
                   :margin "0.2em 0 0.1em"}}
      (display-title ki-title ki-name)]
     [byline author published-at]
     [:p {:style {:font-size "1.05em"
                  :line-height "1.5"
                  :color "#222"
                  :margin 0}}
      output-statement]]))

(defn- ki-card
  [ki]
  (let [edit @(rf/subscribe [::edit])]
    (if (:open? edit) [edit-card ki edit] [static-card ki true])))

(defn- language-selector
  "Content-language chooser for a KI: a badge per supported language, selected one
  highlighted. Calls `on-select` with the chosen code."
  [selected on-select]
  (into [:div {:style {:display "flex"
                       :gap "0.4em"}}]
        (for [l language/languages
              :let [current? (= l selected)]]
          ^{:key l}
          [:button {:on-click #(on-select l)
                    :title (get language/language-name l l)
                    :style {:border (str "1px solid " (if current? "#b9770e" "#ccc"))
                            :background (if current? "#b9770e" "#fff")
                            :color (if current? "#fff" "#b9770e")
                            :text-transform "uppercase"
                            :font-size "0.8em"
                            :font-weight 700
                            :padding "0.25em 0.7em"
                            :border-radius "0.3em"
                            :cursor "pointer"}}
           l])))

(defn creation-form
  "Standalone form to create a new KI (#34). On save it POSTs /agora/api/ki and
  navigates to the new KI's page, where inputs can then be linked."
  []
  (let [{:keys [name title type output-statement submitting?]
         form-lang :lang}
        @(rf/subscribe [::new])
        user @(rf/subscribe [::auth/user])
        lang @(rf/subscribe [::i18n/lang])
        label-style {:font-size "0.8em"
                     :color "#555"
                     :margin-bottom "0.3em"}
        blank? (or (str/blank? name) (str/blank? output-statement))]
    [:div {:style (assoc card-style :margin "1.5em auto")}
     [:h1 {:style {:font-size "1.3em"
                   :margin "0 0 0.8em"}}
      (i18n/t lang :form/new-title)]
     [:div {:style label-style}
      (i18n/t lang :form/name)]
     [:input {:type "text"
              :placeholder (i18n/t lang :form/name-ph)
              :value (or name "")
              :on-change #(rf/dispatch [::new-set :name (.. % -target -value)])
              :style {:width "100%"
                      :box-sizing "border-box"
                      :padding "0.5em"
                      :font-family "inherit"
                      :font-size "0.95em"
                      :border "1px solid #ccc"
                      :border-radius "0.3em"
                      :margin-bottom "0.8em"}}]
     [:div {:style label-style}
      (i18n/t lang :form/title)]
     [:input {:type "text"
              :placeholder (i18n/t lang :form/title-ph)
              :value (or title "")
              :on-change #(rf/dispatch [::new-set :title (.. % -target -value)])
              :style {:width "100%"
                      :box-sizing "border-box"
                      :padding "0.5em"
                      :font-family "inherit"
                      :font-size "0.95em"
                      :border "1px solid #ccc"
                      :border-radius "0.3em"
                      :margin-bottom "0.8em"}}]
     [:div {:style label-style}
      (i18n/t lang :form/type)]
     [:div {:style {:margin-bottom "0.8em"}}
      [type-selector (or type "derived") #(rf/dispatch [::new-set :type %])]]
     [:div {:style label-style}
      (i18n/t lang :form/language)]
     [:div {:style {:margin-bottom "0.8em"}}
      [language-selector (or form-lang lang) #(rf/dispatch [::new-set :lang %])]]
     [:div {:style label-style}
      (i18n/t lang :form/statement)]
     [auto-textarea {:placeholder (i18n/t lang :form/statement-ph)
                     :value (or output-statement "")
                     :on-change #(rf/dispatch [::new-set :output-statement (.. % -target -value)])}]
     [:div {:style {:display "flex"
                    :gap "0.5em"
                    :margin-top "0.9em"}}
      [:button {:on-click (cond
                            (not user) #(rf/dispatch [::auth/open :login])
                            (or blank? submitting?) nil
                            :else #(rf/dispatch [::new-submit]))
                :disabled (boolean (and user (or blank? submitting?)))
                :style {:padding "0.4em 0.9em"
                        :border "none"
                        :background "#b9770e"
                        :color "#fff"
                        :border-radius "0.3em"
                        :opacity (if user 1 0.7)
                        :cursor (if (and user (or blank? submitting?)) "default" "pointer")}}
       (cond
         (not user) (i18n/t lang :form/login-to-create)
         submitting? (i18n/t lang :form/creating)
         :else (i18n/t lang :form/create))]
      [:a {:href (i18n/discover lang)
           :style {:padding "0.4em 0.9em"
                   :border "1px solid #ccc"
                   :background "#fff"
                   :border-radius "0.3em"
                   :text-decoration "none"
                   :color "#444"}}
       (i18n/t lang :form/cancel)]]]))

(defn translation-editor
  "Modal to create a language version of a KI: the source text (read-only, for
  reference) above an editable field pre-filled with a machine-translation
  suggestion the author validates and edits before saving. Rendered at the app
  root; shown only while a translate-form is open."
  []
  (when-let [{:keys [source-lang
                     target-lang
                     source-text
                     source-title
                     translation
                     title
                     suggesting?
                     title-suggesting?
                     saving?]}
             @(rf/subscribe [::translate-form])]
    (let [lang @(rf/subscribe [::i18n/lang])
          label-style {:font-size "0.8em"
                       :color "#555"
                       :margin "0.6em 0 0.3em"}]
      [:div {:on-click #(rf/dispatch [::translate-cancel])
             :style {:position "fixed"
                     :inset 0
                     :z-index 100
                     :background "rgba(0,0,0,0.45)"
                     :display "flex"
                     :align-items "flex-start"
                     :justify-content "center"
                     :padding-top "8vh"}}
       [:div {:on-click #(.stopPropagation %)
              :style {:width "34em"
                      :max-width "92%"
                      :background "#fff"
                      :border-radius "0.6em"
                      :padding "1.4em"
                      :font-family "system-ui, sans-serif"}}
        [:h2 {:style {:margin "0 0 0.3em"
                      :font-size "1.3em"}}
         (str (i18n/t lang :translate/to) " " (get language/language-name target-lang target-lang))]
        [:div {:style label-style}
         (str (i18n/t lang :translate/source)
              " · "
              (get language/language-name source-lang source-lang))]
        ;; source title (read-only) + editable translated title
        [:div {:style {:padding "0.4em 0.7em"
                       :background "#f7f4ec"
                       :border "1px solid #e2ddd2"
                       :border-radius "0.3em"
                       :color "#555"
                       :font-weight 700}}
         source-title]
        [:input {:type "text"
                 :value (if title-suggesting? "" (or title ""))
                 :disabled title-suggesting?
                 :placeholder (when title-suggesting? (i18n/t lang :translate/suggesting))
                 :on-change #(rf/dispatch [::translate-set :title (.. % -target -value)])
                 :style {:width "100%"
                         :box-sizing "border-box"
                         :margin-top "0.3em"
                         :padding "0.4em"
                         :font-family "inherit"
                         :font-size "1.05em"
                         :font-weight 700
                         :border "1px solid #ccc"
                         :border-radius "0.3em"}}]
        ;; source statement (read-only) + editable translated statement
        [:div {:style {:padding "0.6em 0.7em"
                       :margin-top "0.6em"
                       :background "#f7f4ec"
                       :border "1px solid #e2ddd2"
                       :border-radius "0.3em"
                       :color "#555"
                       :font-size "1em"
                       :line-height "1.5"
                       :white-space "pre-wrap"}}
         source-text]
        [:div {:style label-style}
         (i18n/t lang :translate/your)]
        [auto-textarea {:value (if suggesting? "" translation)
                        :disabled suggesting?
                        :placeholder (when suggesting? (i18n/t lang :translate/suggesting))
                        :on-change #(rf/dispatch
                                     [::translate-set :translation (.. % -target -value)])}]
        [:div {:style {:display "flex"
                       :gap "0.5em"
                       :margin-top "0.9em"}}
         [:button {:on-click #(rf/dispatch [::translate-save])
                   :disabled (boolean
                              (or suggesting? title-suggesting? saving? (str/blank? translation)))
                   :style {:padding "0.45em 1em"
                           :border "none"
                           :background "#b9770e"
                           :color "#fff"
                           :border-radius "0.3em"
                           :cursor
                           (if (or suggesting? title-suggesting? saving?) "default" "pointer")}}
          (if saving? (i18n/t lang :translate/creating) (i18n/t lang :translate/create))]
         [:button {:on-click #(rf/dispatch [::translate-cancel])
                   :style {:padding "0.45em 1em"
                           :border "1px solid #ccc"
                           :background "#fff"
                           :border-radius "0.3em"
                           :cursor "pointer"}}
          (i18n/t lang :form/cancel)]]]])))

(defn ki-page
  "The KI page: inputs (removable) + add-input control above, the editable card in
  the middle, successors below, joined by directed connectors. A `+ New KI` link
  sits at the top."
  [{:keys [id inputs successors]
    :as ki}]
  (let [user @(rf/subscribe [::auth/user])
        lang @(rf/subscribe [::i18n/lang])]
    [:div {:style {:display "flex"
                   :flex-direction "column"
                   :align-items "center"
                   :padding "1em 0.6em 2em"}}
     (into [:div {:style {:display "flex"
                          :flex-wrap "wrap"
                          :gap "0.5em"
                          :justify-content "center"
                          ;; top-align so the small "+" control doesn't vertically
                          ;; offset the taller input cards next to it.
                          :align-items "flex-start"}}]
           (concat (for [inp inputs]
                     ^{:key (:id inp)}
                     [mini-card
                      inp
                      (i18n/ki-id lang (:id inp))
                      (when user
                        #(rf/dispatch [::drop-input id (select-keys inp [:name :major])]))])
                   [^{:key "add"} [add-input-control ki]]))
     [connector]
     [ki-card ki]
     [language-mismatch-notice lang ki]
     (when (seq successors)
       [:<>
        [connector]
        (into [:div {:style {:display "flex"
                             :flex-wrap "wrap"
                             :gap "0.5em"
                             :justify-content "center"}}]
              (for [s successors] ^{:key (:id s)} [mini-card s (i18n/ki-id lang (:id s)) nil]))])]))

(defn public-ki-page
  "Read-only public KI page (#35): inputs above, the card (no edit controls) in the
  middle, successors below. Neighbour links point at other public permalinks."
  [{:keys [inputs successors]
    :as ki}]
  (let [lang @(rf/subscribe [::i18n/lang])]
    [:div {:style {:display "flex"
                   :flex-direction "column"
                   :align-items "center"
                   :padding "1em 0.6em 2em"}}
     (when (seq inputs)
       [:<>
        (into [:div {:style {:display "flex"
                             :flex-wrap "wrap"
                             :gap "0.5em"
                             :justify-content "center"}}]
              (for [inp inputs] ^{:key (:id inp)} [mini-card inp (permalink lang inp) nil]))
        [connector]])
     [static-card ki false]
     [language-mismatch-notice lang ki]
     (when (seq successors)
       [:<>
        [connector]
        (into [:div {:style {:display "flex"
                             :flex-wrap "wrap"
                             :gap "0.5em"
                             :justify-content "center"}}]
              (for [s successors] ^{:key (:id s)} [mini-card s (permalink lang s) nil]))])]))

;; ===========================================================================
;; Discoverability page (#36)
;; ===========================================================================

(defn- discover-card
  "A preview card giving a clue of what a KI holds: type, a readable title, and its
  full output statement (never truncated — the card grows to fit)."
  [lang k]
  [:a {:href (permalink lang k)
       :style {:display "flex"
               :flex-direction "column"
               :gap "0.55em"
               :min-height "11em"
               :padding "0.9em 1em"
               :border "1px solid #e2ddd2"
               :border-radius "0.6em"
               :text-decoration "none"
               :color "inherit"
               :background "#fff"
               :box-shadow "0 1px 3px rgba(0,0,0,0.06)"}}
   [:div {:style {:display "flex"
                  :align-items "center"
                  :gap "0.5em"}}
    [type-badge-view (:type k)]
    [:span {:style version-tag-style}
     (str "v" (:major k) "." (:minor k))]]
   [:div {:style {:font-weight 700
                  :font-size "1.02em"
                  :line-height 1.25
                  :color "#2a2723"}}
    (display-title (:title k) (:name k))]
   [:div {:style {:font-size "0.9em"
                  :line-height 1.4
                  :color "#555"
                  :white-space "pre-wrap"}}
    (:output-statement k)]
   [:div {:style {:margin-top "auto"
                  :color "#aaa"
                  :font-size "0.72em"}}
    (str (:visits k) " " (i18n/t lang (if (= 1 (:visits k)) :discover/view :discover/views)))]])

(defn discover-page
  "Public homepage: a visit-weighted sample of KIs (scoped to the current content
  language) as a responsive grid of preview cards that wraps to as many rows as
  the screen width needs. Navigation and search live in the shared header."
  [kis]
  (let [lang @(rf/subscribe [::i18n/lang])]
    [:div {:style {:max-width "72em"
                   :margin "1.5em auto"
                   :padding "0 0.8em"
                   :font-family "system-ui, sans-serif"}}
     [:p {:style {:color "#666"
                  :margin "0 0 1em"}}
      (i18n/t lang :discover/tagline)]
     (if (seq kis)
       (into [:div {:style {:display "grid"
                            :grid-template-columns "repeat(auto-fill, minmax(min(17em, 100%), 1fr))"
                            :gap "0.9em"}}]
             (for [k kis] ^{:key (:id k)} [discover-card lang k]))
       [:div {:style {:color "#aaa"
                      :font-style "italic"}}
        (i18n/t lang :discover/empty)])]))

;; ===========================================================================
;; Loading placeholders (skeletons)
;; ===========================================================================

(defn- skel
  "A shimmering placeholder block (`.agora-skel` / `@keyframes agora-pulse` in the
  ki.html shell)."
  [style]
  [:div {:class "agora-skel"
         :style style}])

(defn- skeleton-card
  "Card-shaped placeholder matching a KI card's silhouette."
  []
  [:div {:style (assoc card-style :display "flex" :flex-direction "column" :gap "0.7em")}
   [skel {:width "9em"
          :height "1.1em"}]
   [skel {:width "70%"
          :height "1.5em"}]
   [skel {:width "6em"
          :height "0.8em"}]
   [skel {:height "0.9em"}]
   [skel {:height "0.9em"}]
   [skel {:width "85%"
          :height "0.9em"}]])

(defn- skeleton-ki-page
  []
  [:div {:style {:display "flex"
                 :flex-direction "column"
                 :align-items "center"
                 :padding "1em 0.6em 2em"}}
   [skeleton-card]])

(defn- skeleton-discover
  []
  [:div {:style {:max-width "72em"
                 :margin "1.5em auto"
                 :padding "0 0.8em"}}
   [skel {:width "22em"
          :max-width "80%"
          :height "1.1em"
          :margin-bottom "1.2em"}]
   (into [:div {:style {:display "grid"
                        :grid-template-columns "repeat(auto-fill, minmax(17em, 1fr))"
                        :gap "0.9em"}}]
         (for [i (range 6)]
           ^{:key i}
           [:div {:style {:display "flex"
                          :flex-direction "column"
                          :gap "0.6em"
                          :min-height "9em"
                          :padding "0.9em 1em"
                          :border "1px solid #e2ddd2"
                          :border-radius "0.6em"
                          :background "#fff"}}
            [skel {:width "6em"
                   :height "1em"}]
            [skel {:width "80%"
                   :height "1.25em"}]
            [skel {:height "0.8em"}]
            [skel {:width "90%"
                   :height "0.8em"}]
            [skel {:width "60%"
                   :height "0.8em"}]]))])

(defn loading-view
  "Skeleton placeholder shown while a page's data loads, matched to the target
  `kind` so the layout does not jump when the content arrives."
  [kind]
  (case kind
    :discover [skeleton-discover]
    [skeleton-ki-page]))

;; ===========================================================================
;; Preferences page — a settings surface meant to grow over time
;; ===========================================================================

(defn- provider-label
  "Human name for how the account signs in."
  [lang provider]
  (case provider
    "google" "Google"
    "facebook" "Facebook"
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

;; ===========================================================================
;; Admin page — prune KI lineages (TNRs)
;; ===========================================================================

(rf/reg-sub ::admin-tnrs (fn [db _] (:admin-tnrs db)))

(def ^:private admin-btn
  {:font-size "0.8em"
   :padding "0.25em 0.7em"
   :border-radius "0.3em"
   :cursor "pointer"
   :border "1px solid #ccc"
   :background "#fff"})

(defn- admin-actions
  "The action cell for one TNR row: Compact / Drop, with a two-step inline confirm
  (no native dialog)."
  [lang confirm t]
  (let [c @confirm
        pending? (and c (= (:name c) (:name t)) (= (:major c) (:major t)))]
    (if pending?
      [:span {:style {:display "inline-flex"
                      :gap "0.3em"
                      :align-items "center"}}
       [:span {:style {:font-size "0.8em"
                       :color "#c92a2a"}}
        (i18n/t lang :admin/confirm)]
       [:button {:on-click (fn []
                             (rf/dispatch
                              [(if (= :drop (:action c)) :agora/admin-drop :agora/admin-compact)
                               (:name t)
                               (:major t)])
                             (reset! confirm nil))
                 :style (assoc admin-btn :border "1px solid #c92a2a" :color "#c92a2a")}
        "✓"]
       [:button {:on-click #(reset! confirm nil)
                 :style admin-btn}
        "✕"]]
      [:span {:style {:display "inline-flex"
                      :gap "0.4em"}}
       [:button {:on-click #(reset! confirm {:name (:name t)
                                             :major (:major t)
                                             :action :compact})
                 :style admin-btn}
        (i18n/t lang :admin/compact)]
       [:button {:on-click #(reset! confirm {:name (:name t)
                                             :major (:major t)
                                             :action :drop})
                 :style (assoc admin-btn :border "1px solid #c92a2a" :color "#c92a2a")}
        (i18n/t lang :admin/drop)]])))

(defn admin-page
  "Maintenance page: every KI lineage (TNR = name + major) with counts, and
  per-row actions to keep only the latest minor (per language) or drop it entirely.
  Logged-in only."
  []
  (r/with-let
   [confirm (r/atom nil)]
   (let [lang @(rf/subscribe [::i18n/lang])
         user @(rf/subscribe [::auth/user])
         tnrs @(rf/subscribe [::admin-tnrs])
         th {:text-align "left"
             :padding "0.4em 0.6em"
             :border-bottom "2px solid #e2ddd2"
             :color "#888"
             :font-size "0.8em"}
         td {:padding "0.4em 0.6em"
             :border-bottom "1px solid #f0eee8"}]
     [:div {:style {:max-width "56em"
                    :margin "1.5em auto"
                    :padding "0 0.8em"
                    :font-family "system-ui, sans-serif"}}
      [:h1 {:style {:font-size "1.3em"
                    :margin "0 0 0.8em"}}
       (i18n/t lang :admin/title)]
      (cond
        (not user) [:div {:style {:color "#888"
                                  :font-style "italic"}}
                    (i18n/t lang :admin/login-required)]
        (not (:admin user)) [:div {:style {:color "#888"
                                           :font-style "italic"}}
                             (i18n/t lang :admin/not-authorized)]
        (empty? tnrs) [:div {:style {:color "#aaa"
                                     :font-style "italic"}}
                       (i18n/t lang :admin/empty)]
        :else [:table {:style {:width "100%"
                               :border-collapse "collapse"}}
               [:thead
                [:tr
                 [:th {:style th}
                  (i18n/t lang :form/name)]
                 [:th {:style th}
                  (i18n/t lang :admin/major)]
                 [:th {:style th}
                  (i18n/t lang :admin/languages)]
                 [:th {:style th}
                  (i18n/t lang :admin/versions)]
                 [:th {:style th}
                  (i18n/t lang :admin/latest)]
                 [:th {:style th}]]]
               (into [:tbody]
                     (for [t tnrs]
                       ^{:key (str (:name t) "/" (:major t))}
                       [:tr
                        [:td {:style (assoc td :font-weight 600)}
                         (:name t)]
                        [:td {:style td}
                         (:major t)]
                        [:td {:style td}
                         (:langs t)]
                        [:td {:style td}
                         (:versions t)]
                        [:td {:style td}
                         (str "v" (:major t) "." (:latest t))]
                        [:td {:style (assoc td :text-align "right")}
                         [admin-actions lang confirm t]]]))])])))
