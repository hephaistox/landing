(ns landing.agora.frontend.document-view
  "The shared Agora document engine — everything common to KIs and articles, so both
  page layers (`ki-view`, `article-view`) sit on top of it and neither depends on the
  other.

  Holds: the graph layout (`node-frame` with input/successor mini-cards and connectors),
  the badges (`kind-badge`/`lang-badge`/`type-badge`), `byline`, `version-picker`, the
  language switcher `languages-control` and the whole translate flow (`translation-editor`
  + `::translate-*` events), `discover-card`/`add-card`/`fab`, `input-drop-fn`, the JSON
  request helper `json-req`, the auth `gated` helper, and the app chrome (header, footer,
  search, discover/landing/preferences/admin pages, loading skeletons).

  KI-page-specific pieces (the KI card, the create/edit forms and their `::edit`/`::new`
  events) live in `ki-view`; article-specific pieces in `article-view`."
  (:require
   [clojure.string                    :as str]
   [landing.agora.document-domain     :as document-domain]
   [landing.agora.frontend.auth       :as auth]
   [landing.agora.frontend.cite       :as cite]
   [landing.agora.frontend.fmt        :as fmt]
   [landing.agora.frontend.i18n       :as i18n]
   [landing.agora.frontend.ui-commons :as ui]
   [landing.language                  :as language]
   [re-frame.core                     :as rf]
   [reagent.core                      :as r]
   [superstructor.re-frame.fetch-fx]))

;; ===========================================================================
;; Types
;; ===========================================================================

(defn kind-badge
  "Coloured kind badge (colour from the domain) with a label localized to the current
  UI language. Renders nothing when `kind` is missing — a KI should always carry one,
  but a nil must never reach `i18n/t`: `(keyword \"kind\" nil)` yields a keyword whose
  name is nil, which blows up when hashed/looked up in the dict.

  With `{:link? true}` the badge links to the KI that *defines* this type (slug
  `type-<kind>`, seeded by `landing.agora.types-seed`) — the graph self-hosting its own
  vocabulary — and, reusing `cite/node-link`, shows that definition in the same hover
  card as an article citation. Off by default because the badge is often rendered
  *inside* a card anchor, where nesting a link would be invalid HTML; enable it only
  where the badge stands on its own (e.g. the node page header)."
  ([kind] (kind-badge kind nil))
  ([kind {:keys [link?]}]
   (when-let [kind (not-empty (some-> kind
                                      name))]
     (let [lang @(rf/subscribe [::i18n/lang])
           style {:display "inline-block"
                  :background (get document-domain/kind-color kind "#666")
                  :color "#fff"
                  :font-size "0.7em"
                  :font-weight 700
                  :letter-spacing "0.05em"
                  :text-transform "uppercase"
                  :padding "0.2em 0.6em"
                  :border-radius "0.25em"}
           label (i18n/t lang (keyword "kind" kind))
           pill [:span {:style style}
                 label]]
       (if-let [def-ref (when link? (get document-domain/kind-def kind))]
         ;; the kind ↔ definition-KI link is domain data (name + major); type is `ki`,
         ;; minor resolves to latest via by-major, lang is the reader's.
         [cite/node-link (assoc def-ref :lang lang) pill]
         pill)))))

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

(defn byline
  "The document's authorship line: 'author · date'. The author (copper) links to their
  profile page when `author-id` (the owning account) is given — seeded/unowned documents
  have no id and render as plain text. Omitted entirely when there is no author."
  ([author published-at] (byline author published-at nil))
  ([author published-at author-id]
   (let [lang @(rf/subscribe [::i18n/lang])]
     [:div {:style {:color "#888"
                    :font-size "0.8em"
                    :margin-bottom "0.7em"}}
      (when author
        [:span
         (if author-id
           [:a {:href (i18n/author lang author-id)
                :style {:color "#b9770e"
                        :font-weight 600
                        :text-decoration "none"}}
            author]
           [:span {:style {:color "#b9770e"
                           :font-weight 600}}
            author])
         " · "])
      (or (fmt/utc published-at) "—")])))

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
    :keys [id type major translations]
    :as node}]
  (r/with-let
   [open? (r/atom false)]
   (let [user @(rf/subscribe [::auth/user])
         ;; the node's text (unified) seeds the translation; permalink builder is type-specific
         source-text (cite/node-text node)
         lang-href (fn [l entry]
                     (if (= type "article") (i18n/article-permalink l entry) (i18n/ki l entry)))
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
         [ui/on-escape #(reset! open? false)]
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
               entry [:a {:href (lang-href l entry)
                          :on-click #(reset! open? false)
                          :style {:padding "0.4em 0.5em"
                                  :border-radius "0.3em"
                                  :text-decoration "none"
                                  :color "#b9770e"}}
                      (get language/language-name l l)]
               :else [:button {:on-click (fn []
                                           (reset! open? false)
                                           (rf/dispatch [::translate-open
                                                         {:doc-id id
                                                          :type type
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

(defn json-req
  "A JSON `:fetch` request map. `on-failure` is explicit so the helper is decoupled
  from any one screen's error handler (the edit/create flows pass `[::op-failed]`, the
  translate flow its own)."
  [method url body on-success on-failure]
  {:method method
   :url url
   :headers {"Content-Type" "application/json"
             "Accept" "application/json"}
   :body (js/JSON.stringify (clj->js body))
   :response-content-types {#"application/json" :json}
   :on-success on-success
   :on-failure on-failure})

(rf/reg-sub ::translate-form (fn [db _] (::translate-form db)))

(rf/reg-event-fx
 ::translate-open
 (fn [{:keys [db]} [_ {:keys [doc-id type source-lang target-lang source-text source-title]}]]
   {:db (assoc db
               ::translate-form
               {:doc-id doc-id
                :type (or type "ki")
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
                   (let [{:keys [doc-id type target-lang title translation]} (::translate-form db)]
                     {:db (assoc-in db [::translate-form :saving?] true)
                      :fetch (json-req :post
                                       (str "/agora/api/" (or type "ki") "/" doc-id "/translate")
                                       {:lang target-lang
                                        :title title
                                        :text translation}
                                       [::translate-saved target-lang]
                                       [::translate-failed])})))

(rf/reg-event-db ::translate-failed
                 (fn [db [_ resp]]
                   (js/console.error "[agora] translate failed:" (clj->js resp))
                   (assoc-in db [::translate-form :saving?] false)))

(rf/reg-event-fx ::translate-saved
                 (fn [{:keys [db]} [_ target-lang resp]]
                   (let [{:keys [id type]} (:body resp)]
                     (if id
                       {:db (dissoc db ::translate-form)
                        :dispatch [:agora/goto
                                   (if (= type "article")
                                     (i18n/article target-lang id)
                                     (i18n/ki-id target-lang id))]}
                       {:db (assoc-in db [::translate-form :saving?] false)}))))

;; ---- Create a new KI (standalone form, #34) ----

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
                [kind-badge (:kind k)]
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

(defn gated
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
  "Shared Agora header (Hephaistox dark/copper theme): the two discover links, search
  and auth controls. Creation is a `+` card at the end of each discover grid, not a
  header link. The interface language is a preference, set on the Preferences page —
  not here; a signed-in visitor gets the editable page automatically when viewing a KI."
  []
  (let [lang @(rf/subscribe [::i18n/lang])]
    [:header {:class "agora-header"
              :style {:background "#1b1a17"
                      :color "#e8e2d6"
                      :border-bottom "2px solid #b9770e"}}
     [:a {:href (i18n/home lang)
          :style {:font-family "Georgia, 'Cormorant Garamond', serif"
                  :font-size "1.4em"
                  :font-weight 700
                  :letter-spacing "0.03em"
                  :color "#d99a2b"
                  :text-decoration "none"}}
      "Agora"]
     ;; Just the two discover links; creation is a `+` card on each discover grid.
     (let [link (fn [label href] [:a {:key href
                                      :href href
                                      :style {:color "#e8e2d6"
                                              :text-decoration "none"
                                              :opacity 0.85}}
                                  label])]
       [:nav {:style {:display "flex"
                      :align-items "center"
                      :gap "1.1em"
                      :font-size "0.9em"
                      :flex-wrap "wrap"}}
        (link (i18n/t lang :nav/discover-ki) (i18n/discover lang))
        (link (i18n/t lang :nav/discover-articles) (i18n/articles lang))])
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
  "Current version; clicking reveals an in-order strip of every version. `link-fn`
  builds a version's href from its id (type-specific — KI or article)."
  [{:keys [major minor versions]} link-fn]
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
                [:a {:href (link-fn (:id v))
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
    c-type :kind
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
     [kind-badge c-type]
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

(defn- skeleton-mini-card
  "Placeholder shown while a neighbour's KI loads by id."
  []
  [:div {:class "agora-skel"
         :style {:width "16em"
                 :max-width "100%"
                 :height "3.4em"
                 :border-radius "0.4em"}}])

(defn neighbour-card
  "A neighbour (input/successor) rendered from its `id`: fetches its KI by id (into
  the shared cache) and shows a `mini-card`, or a skeleton while loading. `link-fn`
  builds the href from the loaded doc; `drop-fn` (optional) is a fn of the doc → the
  ✕ on-click."
  [id _opts]
  (rf/dispatch [:agora/ensure-ki id])
  (fn [id {:keys [link-fn drop-fn]}]
    (if-let [doc @(rf/subscribe [:agora/doc id])]
      [mini-card doc (link-fn doc) (when drop-fn (drop-fn doc))]
      [skeleton-mini-card])))

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

(def card-style
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

(defn translation-editor
  "Modal to create a language version of a document (KI or article): the source text
  (read-only, for reference) above an editable field pre-filled with a
  machine-translation suggestion the author validates and edits before saving.
  Rendered at the app root; shown only while a translate-form is open."
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
       [ui/on-escape #(rf/dispatch [::translate-cancel])]
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
        ;; source text (read-only) + editable translation
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

(defn- neighbours-row
  "A centered wrap-row of neighbour cards (a node's input or successor KIs). `drop-fn`
  (optional, inputs only) → a ✕ on each card that removes that input link."
  [neighbours link-fn drop-fn]
  (into [:div {:style {:display "flex"
                       :flex-wrap "wrap"
                       :gap "0.5em"
                       :justify-content "center"}}]
        (for [n neighbours]
          ^{:key (:id n)}
          [neighbour-card
           (:id n)
           {:link-fn link-fn
            :drop-fn drop-fn}])))

(defn input-drop-fn
  "For an editable node page: a fn of an input's loaded doc → the ✕ on-click that drops
  that input link (a new minor of `node-id`, type `type`). Removal lives in the input
  field, not in the text. nil when the viewer isn't logged in — removal is an authoring
  action, so anonymous readers see no ✕."
  [type node-id]
  (when @(rf/subscribe [::auth/user])
    (fn [input-doc]
      (fn [e]
        (.preventDefault e)
        (.stopPropagation e)
        (rf/dispatch [:agora/drop-input
                      type
                      node-id
                      {:name (:name input-doc)
                       :major (:major input-doc)}])))))

(defn node-frame
  "The shared node layout for ANY type: the input KIs above, the `central` card in the
  middle, the successor KIs below, joined by directed connectors. `link-fn` builds a
  neighbour's href from its loaded doc. Inputs are the KIs cited in the node's text,
  so KIs and articles render the same graph neighbourhood. `input-drop?` (optional) is a
  fn of an input-doc → its ✕ on-click (see `input-drop-fn`); passed only for editable
  pages, and only to the inputs row."
  ([doc central link-fn] (node-frame doc central link-fn nil))
  ([{:keys [inputs successors]
     :as doc}
    central
    link-fn
    input-drop?]
   (let [lang @(rf/subscribe [::i18n/lang])]
     [:div {:style {:display "flex"
                    :flex-direction "column"
                    :align-items "center"
                    :padding "1em 0.6em 2em"}}
      (when (seq inputs) [:<> [neighbours-row inputs link-fn input-drop?] [connector]])
      central
      [language-mismatch-notice lang doc]
      (when (seq successors) [:<> [connector] [neighbours-row successors link-fn nil]])])))

(defn type-badge
  "A node's badge: a KI's coloured kind badge, or a neutral grey 'Article' chip."
  [{:keys [type kind]}]
  (if (= "article" type)
    [:span {:style {:font-size "0.62em"
                    :font-weight 700
                    :letter-spacing "0.05em"
                    :text-transform "uppercase"
                    :color "#fff"
                    :background "#8a8175"
                    :padding "0.2em 0.6em"
                    :border-radius "0.25em"}}
     (i18n/t @(rf/subscribe [::i18n/lang]) :type/article)]
    [kind-badge kind]))

(defn discover-card
  "A preview card for a node (KI or article) in a discover grid: its badge (a KI's
  kind, or an 'Article' chip), version, title, an excerpt of its text (citations
  flattened, clamped so the grid stays even), and the publication date."
  [lang node]
  (let [article? (= "article" (:type node))
        excerpt (cite/plain-text (cite/node-text node))]
    [:a {:href (if article? (i18n/article-permalink lang node) (permalink lang node))
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
      [type-badge node]
      [:span {:style version-tag-style}
       (str "v" (:major node) "." (:minor node))]]
     [:div {:style {:font-weight 700
                    :font-size "1.02em"
                    :line-height 1.25
                    :color "#2a2723"}}
      (display-title (:title node) (:name node))]
     [:div {:style {:font-size "0.9em"
                    :line-height 1.4
                    :color "#555"
                    :white-space "pre-wrap"
                    :display "-webkit-box"
                    :-webkit-line-clamp 5
                    :-webkit-box-orient "vertical"
                    :overflow "hidden"}}
      excerpt]
     [:div {:style {:margin-top "auto"
                    :color "#888"
                    :font-size "0.8em"}}
      (when-let [a (:author node)]
        [:span
         [:span {:style {:color "#b9770e"
                         :font-weight 600}}
          a]
         " · "])
      (or (fmt/utc (:published-at node)) "—")]]))

(defn add-card
  "A dashed 'create' tile for the end of a discover grid — a large + linking to `href`.
  Auth is handled by the destination form (it shows a log-in prompt when needed)."
  [href label]
  [:a {:href href
       :title label
       :style {:display "flex"
               :flex-direction "column"
               :align-items "center"
               :justify-content "center"
               :gap "0.35em"
               :min-height "6em"
               :padding "0.9em 1em"
               :border "2px dashed #cbb68f"
               :border-radius "0.6em"
               :background "transparent"
               :color "#b9770e"
               :text-decoration "none"}}
   [:span {:style {:font-size "2.4em"
                   :line-height "0.9"
                   :font-weight 300}}
    "+"]
   [:span {:style {:font-size "0.85em"
                   :font-weight 600}}
    label]])

(defn fab
  "A mobile-only floating '+' create button, fixed bottom-right so it stays reachable
  without scrolling to the grid's trailing add-card. Hidden ≥640px by the shell's
  `.agora-fab` rule (desktop uses the in-grid card). Links to `href`."
  [href label]
  [:a {:class "agora-fab"
       :href href
       :title label
       :aria-label label}
   "+"])

(defn- landing-hero
  "The marketing banner atop the landing page: eyebrow, pitch, the primary explore /
  publish actions, and a reassurance line — so the value prop lands above the fold."
  [lang]
  [:section {:style {:background "linear-gradient(160deg, #1b1a17, #262019)"
                     :color "#e8e2d6"
                     :border-radius "0.8em"
                     :padding "2.6em 1.4em"
                     :margin-bottom "1.4em"
                     :text-align "center"}}
   [:div {:style {:font-size "0.78em"
                  :font-weight 700
                  :text-transform "uppercase"
                  :letter-spacing "0.14em"
                  :color "#d69a3a"
                  :margin-bottom "0.9em"}}
    (i18n/t lang :home/eyebrow)]
   [:h1 {:style {:font-family "Georgia, 'Cormorant Garamond', serif"
                 :font-size "clamp(1.7em, 4.4vw, 2.7em)"
                 :line-height "1.16"
                 :margin "0 0 0.5em"
                 :color "#f0e6d2"}}
    (i18n/t lang :landing/headline)]
   [:p {:style {:max-width "42em"
                :margin "0 auto 1.5em"
                :font-size "1.08em"
                :line-height "1.6"
                :color "#c9c1b2"}}
    (i18n/t lang :home/subtitle)]
   [:div {:style {:display "flex"
                  :flex-wrap "wrap"
                  :gap "0.7em"
                  :justify-content "center"}}
    [:a {:href (i18n/discover lang)
         :style {:padding "0.65em 1.4em"
                 :background "#b9770e"
                 :color "#fff"
                 :border-radius "0.4em"
                 :font-weight 600
                 :text-decoration "none"}}
     (i18n/t lang :home/cta-explore)]
    [:a {:href (i18n/new-ki lang)
         :style {:padding "0.65em 1.4em"
                 :background "transparent"
                 :color "#e8e2d6"
                 :border "1px solid #b9770e"
                 :border-radius "0.4em"
                 :text-decoration "none"}}
     (i18n/t lang :home/cta-publish)]]
   [:div {:style {:margin-top "1.3em"
                  :font-size "0.82em"
                  :color "#8f8776"}}
    (i18n/t lang :home/trust)]])

(defn- landing-spotlight
  "A live example — one real KI from the graph, rendered prominently so a first-time
  visitor sees what a Knowledge Item is."
  [lang k]
  [:a {:href (permalink lang k)
       :style {:display "block"
               :border "1px solid #e2ddd2"
               :border-left "4px solid #b9770e"
               :border-radius "0.6em"
               :background "#fff"
               :padding "1.1em 1.3em"
               :margin-bottom "1.6em"
               :text-decoration "none"
               :color "inherit"}}
   [:div {:style {:font-size "0.72em"
                  :text-transform "uppercase"
                  :letter-spacing "0.06em"
                  :color "#b9770e"
                  :margin-bottom "0.55em"}}
    (i18n/t lang :landing/example-label)]
   [:div {:style {:display "flex"
                  :align-items "center"
                  :gap "0.6em"
                  :margin-bottom "0.45em"}}
    [kind-badge (:kind k)]
    [:span {:style {:font-weight 700
                    :font-size "1.15em"}}
     (or (:title k) (:name k))]]
   [:p {:style {:margin "0 0 0.5em"
                :color "#333"
                :line-height "1.55"}}
    (cite/node-text k)]
   [:span {:style {:color "#b9770e"
                   :font-weight 600
                   :font-size "0.9em"}}
    (i18n/t lang :landing/explore)]])

(defn- home-section-heading
  "Serif section title, centered — the recurring divider between landing sections."
  [text]
  [:h2 {:style {:font-family "Georgia, 'Cormorant Garamond', serif"
                :font-size "clamp(1.4em, 3.2vw, 2em)"
                :color "#1b1a17"
                :text-align "center"
                :margin "0 0 0.8em"}}
   text])

(defn- home-mini-node
  "One illustrative card in the reasoning diagram: a coloured type tag over its text.
  Pure illustration (not live data), so the labels are plain words, not KI kinds."
  [tag color text]
  [:div {:style {:flex "1 1 15em"
                 :max-width "22em"
                 :background "#fff"
                 :border "1px solid #e6e0d4"
                 :border-left (str "4px solid " color)
                 :border-radius "0.55em"
                 :padding "0.85em 1em"
                 :box-shadow "0 1px 3px rgba(0,0,0,0.06)"
                 :text-align "left"}}
   [:div {:style {:font-size "0.66em"
                  :font-weight 700
                  :text-transform "uppercase"
                  :letter-spacing "0.09em"
                  :color color
                  :margin-bottom "0.35em"}}
    tag]
   [:div {:style {:color "#2a2621"
                  :line-height "1.45"}}
    text]])

(defn- reasoning-diagram
  "The page's main 'picture': a self-contained illustration of a Knowledge Item
  chain — a definition and an observation feeding a conclusion, plus a live-looking
  objection — so a newcomer instantly sees what the graph is made of."
  [lang]
  [:div {:style {:background "#faf7f1"
                 :border "1px solid #ece5d8"
                 :border-radius "0.8em"
                 :padding "1.7em 1.3em"}}
   [:div {:style {:display "flex"
                  :flex-wrap "wrap"
                  :gap "0.9em"
                  :justify-content "center"}}
    [home-mini-node (i18n/t lang :home/tag-definition) "#6741d9" (i18n/t lang :home/ex-definition)]
    [home-mini-node
     (i18n/t lang :home/tag-observation)
     "#0b7285"
     (i18n/t lang :home/ex-observation)]]
   [:div {:style {:text-align "center"
                  :color "#b9770e"
                  :font-size "1.5em"
                  :line-height 1
                  :margin "0.55em 0"}}
    "↓"]
   [:div {:style {:display "flex"
                  :justify-content "center"}}
    [home-mini-node (i18n/t lang :home/tag-conclusion) "#2c5aa0" (i18n/t lang :home/ex-conclusion)]]
   [:div {:style {:max-width "30em"
                  :margin "1.2em auto 0"
                  :background "#fff4f4"
                  :border "1px dashed #e0a0a0"
                  :border-radius "0.55em"
                  :padding "0.65em 0.95em"
                  :color "#8a3a3a"
                  :font-size "0.92em"
                  :line-height "1.45"}}
    [:span {:style {:font-weight 700
                    :margin-right "0.45em"}}
     "⚔"]
    (i18n/t lang :home/ex-objection)]])

(defn- home-step
  "One numbered step in the 'how it works' row."
  [n title body]
  [:div {:style {:flex "1 1 14em"
                 :min-width "12em"}}
   [:div {:style {:width "2.2em"
                  :height "2.2em"
                  :border-radius "50%"
                  :background "#b9770e"
                  :color "#fff"
                  :font-weight 700
                  :display "flex"
                  :align-items "center"
                  :justify-content "center"
                  :margin-bottom "0.6em"}}
    n]
   [:h3 {:style {:margin "0 0 0.3em"
                 :color "#1b1a17"
                 :font-size "1.05em"}}
    title]
   [:p {:style {:margin 0
                :color "#5c5648"
                :line-height "1.5"}}
    body]])

(defn- home-feature
  "One feature tile: an emoji glyph, a title and a one-liner."
  [glyph title body]
  [:div {:style {:background "#fff"
                 :border "1px solid #ece5d8"
                 :border-radius "0.6em"
                 :padding "1.1em"}}
   [:div {:style {:font-size "1.6em"
                  :margin-bottom "0.35em"}}
    glyph]
   [:h3 {:style {:margin "0 0 0.25em"
                 :font-size "1em"
                 :color "#1b1a17"}}
    title]
   [:p {:style {:margin 0
                :color "#5c5648"
                :font-size "0.92em"
                :line-height "1.5"}}
    body]])

(defn- prediction-example
  "One illustrated example prediction: a trigger glyph, a PREDICTION badge, the claim,
  and a line on how/when it resolves. Copy-only teaser — no evaluation behind it yet."
  [glyph claim resolves]
  [:div {:style {:flex "1 1 16em"
                 :background "#fff"
                 :border "1px solid #cfe0e3"
                 :border-left "4px solid #0b7285"
                 :border-radius "0.6em"
                 :padding "1.1em 1.2em"}}
   [:div {:style {:display "flex"
                  :align-items "center"
                  :gap "0.5em"
                  :margin-bottom "0.55em"}}
    [:span {:style {:font-size "1.35em"}} glyph]
    [kind-badge "prediction"]]
   [:p {:style {:margin "0 0 0.6em"
                :color "#1b1a17"
                :font-weight 600
                :font-size "1.02em"
                :line-height "1.4"}}
    claim]
   [:p {:style {:margin 0
                :color "#5c5648"
                :font-size "0.88em"
                :line-height "1.5"}}
    resolves]])

(defn- prediction-teaser
  "The prediction pitch: a lead, two example predictions (date-triggered vs
  event-triggered — the two ways a claim resolves), and a note on settlement."
  [lang]
  [:div
   [:p {:style {:max-width "40em"
                :margin "0 auto 1.4em"
                :text-align "center"
                :color "#5c5648"
                :line-height "1.6"}}
    (i18n/t lang :home/predict-lead)]
   [:div {:style {:display "flex"
                  :flex-wrap "wrap"
                  :gap "1em"
                  :justify-content "center"}}
    [prediction-example "📅"
     (i18n/t lang :home/predict-date-claim)
     (i18n/t lang :home/predict-date-resolve)]
    [prediction-example "⚡"
     (i18n/t lang :home/predict-event-claim)
     (i18n/t lang :home/predict-event-resolve)]]
   [:p {:style {:max-width "40em"
                :margin "1.4em auto 0"
                :text-align "center"
                :color "#8a7a55"
                :font-size "0.9em"
                :font-style "italic"}}
    (i18n/t lang :home/predict-footer)]])

(defn landing-page
  "The Agora home/landing page (`/agora/<lang>`): a marketing hero, an illustrated
  'anatomy of a claim', the pain it solves, how it works, a feature grid, the
  prediction pitch, a live example KI from the graph, and a closing call to action.
  The full browse grid lives on the discover page."
  [kis]
  (let [lang @(rf/subscribe [::i18n/lang])]
    [:div {:style {:max-width "60em"
                   :margin "1.5em auto"
                   :padding "0 0.9em"
                   :font-family "system-ui, sans-serif"}}
     [landing-hero lang]
     ;; Anatomy of a claim — the illustrated centrepiece
     [:section {:style {:margin "2.6em 0"}}
      [home-section-heading (i18n/t lang :home/anatomy-title)]
      [:p {:style {:max-width "40em"
                   :margin "0 auto 1.2em"
                   :text-align "center"
                   :color "#5c5648"
                   :line-height "1.6"}}
       (i18n/t lang :home/anatomy-lead)]
      [reasoning-diagram lang]]
     ;; The pain it solves
     [:section {:style {:background "#1b1a17"
                        :color "#e8e2d6"
                        :border-radius "0.8em"
                        :padding "2.2em 1.6em"
                        :margin "2.6em 0"
                        :text-align "center"}}
      [:h2 {:style {:font-family "Georgia, 'Cormorant Garamond', serif"
                    :font-size "clamp(1.4em, 3.4vw, 2em)"
                    :margin "0 0 0.55em"
                    :color "#f0e6d2"}}
       (i18n/t lang :home/problem-title)]
      [:p {:style {:max-width "40em"
                   :margin "0 auto"
                   :line-height "1.7"
                   :color "#c9c1b2"}}
       (i18n/t lang :home/problem-body)]]
     ;; How it works — three steps
     [:section {:style {:margin "2.6em 0"}}
      [home-section-heading (i18n/t lang :home/how-title)]
      [:div {:style {:display "flex"
                     :flex-wrap "wrap"
                     :gap "1.4em"}}
       [home-step "1" (i18n/t lang :home/how-1-title) (i18n/t lang :home/how-1-body)]
       [home-step "2" (i18n/t lang :home/how-2-title) (i18n/t lang :home/how-2-body)]
       [home-step "3" (i18n/t lang :home/how-3-title) (i18n/t lang :home/how-3-body)]]]
     ;; Feature grid
     [:section {:style {:margin "2.6em 0"}}
      [home-section-heading (i18n/t lang :home/features-title)]
      (into
       [:div {:style {:display "grid"
                      :grid-template-columns "repeat(auto-fit, minmax(min(16em, 100%), 1fr))"
                      :gap "0.9em"}}]
       [[home-feature "🔗" (i18n/t lang :home/feat-terms-title) (i18n/t lang :home/feat-terms-body)]
        [home-feature
         "⚔️"
         (i18n/t lang :home/feat-objection-title)
         (i18n/t lang :home/feat-objection-body)]
        [home-feature
         "🌳"
         (i18n/t lang :home/feat-versions-title)
         (i18n/t lang :home/feat-versions-body)]
        [home-feature "🕒" (i18n/t lang :home/feat-time-title) (i18n/t lang :home/feat-time-body)]
        [home-feature
         "📉"
         (i18n/t lang :home/feat-confidence-title)
         (i18n/t lang :home/feat-confidence-body)]
        [home-feature
         "🌍"
         (i18n/t lang :home/feat-lang-title)
         (i18n/t lang :home/feat-lang-body)]])]
     ;; Predictions — put a claim on the record
     [:section {:style {:margin "2.6em 0"}}
      [home-section-heading (i18n/t lang :home/predict-title)]
      [prediction-teaser lang]]
     ;; A real KI from the graph
     (when-let [k (first kis)]
       [:section {:style {:margin "2.6em 0"}}
        [home-section-heading (i18n/t lang :home/live-title)]
        [landing-spotlight lang k]])
     ;; Closing call to action
     [:section {:style {:background "linear-gradient(160deg, #b9770e, #8a5709)"
                        :color "#fff"
                        :border-radius "0.8em"
                        :padding "2.4em 1.6em"
                        :margin "2.6em 0 1em"
                        :text-align "center"}}
      [:h2 {:style {:font-family "Georgia, 'Cormorant Garamond', serif"
                    :font-size "clamp(1.4em, 3.4vw, 2em)"
                    :margin "0 0 0.5em"}}
       (i18n/t lang :home/cta-title)]
      [:p {:style {:max-width "36em"
                   :margin "0 auto 1.3em"
                   :line-height "1.6"
                   :color "#fbe6cf"}}
       (i18n/t lang :home/cta-body)]
      [:div {:style {:display "flex"
                     :flex-wrap "wrap"
                     :gap "0.7em"
                     :justify-content "center"}}
       [:a {:href (i18n/new-ki lang)
            :style {:padding "0.7em 1.5em"
                    :background "#1b1a17"
                    :color "#f0e6d2"
                    :border-radius "0.4em"
                    :font-weight 600
                    :text-decoration "none"}}
        (i18n/t lang :home/cta-publish)]
       [:a {:href (i18n/discover lang)
            :style {:padding "0.7em 1.5em"
                    :background "transparent"
                    :color "#fff"
                    :border "1px solid rgba(255,255,255,0.7)"
                    :border-radius "0.4em"
                    :text-decoration "none"}}
        (i18n/t lang :home/cta-explore)]]]]))

(defn discover-page
  "Focused KI discovery (`/agora/<lang>/discover`): a responsive grid of recent KIs,
  ending with a `+` card, plus a mobile FAB. Search lives in the shared header."
  [kis]
  (let [lang @(rf/subscribe [::i18n/lang])]
    [:div {:style {:max-width "72em"
                   :margin "1.5em auto"
                   :padding "0 0.8em"
                   :font-family "system-ui, sans-serif"}}
     [:h1 {:style {:font-size "1.4em"
                   :margin "0 0 0.2em"
                   :color "#1b1a17"}}
      (i18n/t lang :discover/heading)]
     [:p {:style {:color "#666"
                  :margin "0 0 1.1em"}}
      (i18n/t lang :discover/tagline)]
     (into [:div {:style {:display "grid"
                          :grid-template-columns "repeat(auto-fill, minmax(min(17em, 100%), 1fr))"
                          :gap "0.9em"}}]
           (conj (mapv (fn [k] ^{:key (:id k)} [discover-card lang k]) kis)
                 ^{:key "__add__"} [add-card (i18n/new-ki lang) (i18n/t lang :nav/new-ki)]))
     [fab (i18n/new-ki lang) (i18n/t lang :nav/new-ki)]]))

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
    (:home :discover :articles) [skeleton-discover]
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

(defn language-selector
  "A language chooser: a badge per supported language, the selected one highlighted.
  Calls `on-select` with the chosen code. Shared by the preferences page (interface
  language) and the KI creation form (content language)."
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
(rf/reg-sub ::admin-issues (fn [db _] (:admin-issues db)))

(defn- consistency-panel
  "Admin: nodes with dangling references (a broken input / citation). Shown above the
  lineage table so data errors surface immediately."
  [lang issues]
  (let [bad? (seq issues)]
    [:div {:style {:margin "0 0 1.4em"
                   :border (str "1px solid " (if bad? "#e0a0a0" "#cfe0cf"))
                   :border-radius "0.5em"
                   :padding "0.8em 1em"
                   :background (if bad? "#fff4f4" "#f2f8f2")}}
     [:div {:style {:font-weight 700
                    :color (if bad? "#8a3a3a" "#3a7a3a")
                    :margin-bottom (if bad? "0.6em" "0")}}
      (str (if bad? "⚠ " "✓ ")
           (i18n/t lang :admin/issues-title)
           (when bad? (str " (" (count issues) ")")))]
     (if-not bad?
       [:div {:style {:color "#3a7a3a"
                      :font-size "0.9em"}}
        (i18n/t lang :admin/issues-none)]
       (into
        [:ul {:style {:margin 0
                      :padding-left "1.2em"
                      :font-size "0.9em"}}]
        (for [i issues]
          ^{:key (str (:type i) "/" (:name i) "/" (:lang i) "/" (:major i) "." (:minor i))}
          [:li {:style {:margin "0.25em 0"}}
           [cite/node-link
            i
            (str (:type i)
                 " · "
                 (or (:title i) (:name i))
                 " ("
                 (:lang i)
                 " v"
                 (:major i)
                 "."
                 (:minor i)
                 ")")]
           [:span {:style {:color "#666"}}
            (when (seq (:broken i))
              (str " → " (i18n/t lang :admin/issues-broken)
                   ": " (str/join ", " (map (fn [b] (str (:name b) "@" (:major b))) (:broken i)))))
            (when (seq (:self i))
              (str " → " (i18n/t lang :admin/issues-self)
                   ": " (str/join ", "
                                  (map (fn [b] (str (:name b) "@" (:major b))) (:self i)))))]])))]))

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
        same? (and c
                   (= (:type c) (:type t))
                   (= (:name c) (:name t))
                   (= (:lang c) (:lang t))
                   (= (:major c) (:major t)))
        target {:type (:type t)
                :name (:name t)
                :lang (:lang t)
                :major (:major t)}]
    (if same?
      [:span {:style {:display "inline-flex"
                      :gap "0.3em"
                      :align-items "center"}}
       [:span {:style {:font-size "0.8em"
                       :color "#c92a2a"}}
        (i18n/t lang :admin/confirm)]
       [:button {:on-click (fn []
                             (rf/dispatch
                              [(if (= :drop (:action c)) :agora/admin-drop :agora/admin-compact)
                               (:type t)
                               (:name t)
                               (:lang t)
                               (:major t)])
                             (reset! confirm nil))
                 :style (assoc admin-btn :border "1px solid #c92a2a" :color "#c92a2a")}
        "✓"]
       [:button {:on-click #(reset! confirm nil)
                 :style admin-btn}
        "✕"]]
      [:span {:style {:display "inline-flex"
                      :gap "0.4em"}}
       [:button {:on-click #(reset! confirm (assoc target :action :compact))
                 :style admin-btn}
        (i18n/t lang :admin/compact)]
       [:button {:on-click #(reset! confirm (assoc target :action :drop))
                 :style (assoc admin-btn :border "1px solid #c92a2a" :color "#c92a2a")}
        (i18n/t lang :admin/drop)]])))

(defn admin-page
  "Maintenance page: every KI lineage (TNR = name + major) with counts, and
  per-row actions to keep only the latest minor (per language) or drop it entirely.
  Logged-in only."
  []
  (r/with-let
   [confirm (r/atom nil) lang-filter (r/atom nil)]
   (let [lang @(rf/subscribe [::i18n/lang])
         user @(rf/subscribe [::auth/user])
         tnrs @(rf/subscribe [::admin-tnrs])
         issues @(rf/subscribe [::admin-issues])
         ;; the admin's local language: the chosen filter, or your interface language
         ;; until you pick one. `:all` shows every language (and a Language column).
         flt (if (nil? @lang-filter) lang @lang-filter)
         all? (= :all flt)
         shown (if all? tnrs (filter #(= flt (:lang %)) tnrs))
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
      (when (:admin user) [consistency-panel lang issues])
      ;; Language filter — defaults to your selected language (so the table matches it);
      ;; "All languages" shows every version and adds a Language column.
      (when (:admin user)
        [:div {:style {:display "flex"
                       :align-items "center"
                       :gap "0.5em"
                       :margin "0 0 1em"}}
         [:span {:style {:font-size "0.85em"
                         :color "#888"}}
          (i18n/t lang :admin/language)]
         [:select {:value (if all? "__all__" flt)
                   :on-change #(let [v (.. % -target -value)]
                                 (reset! lang-filter (if (= v "__all__") :all v)))
                   :style {:font-size "0.9em"
                           :padding "0.25em 0.4em"
                           :border "1px solid #ccc"
                           :border-radius "0.3em"}}
          [:option {:value "__all__"}
           (i18n/t lang :admin/all-langs)]
          (for [l language/languages]
            ^{:key l}
            [:option {:value l}
             (get language/language-name l l)])]])
      (cond
        (not user) [:div {:style {:color "#888"
                                  :font-style "italic"}}
                    (i18n/t lang :admin/login-required)]
        (not (:admin user)) [:div {:style {:color "#888"
                                           :font-style "italic"}}
                             (i18n/t lang :admin/not-authorized)]
        (empty? shown) [:div {:style {:color "#aaa"
                                      :font-style "italic"}}
                        (i18n/t lang :admin/empty)]
        :else [:table {:style {:width "100%"
                               :border-collapse "collapse"}}
               [:thead
                [:tr
                 [:th {:style th}
                  (i18n/t lang :admin/type)]
                 [:th {:style th}
                  (i18n/t lang :form/name)]
                 (when all?
                   [:th {:style th}
                    (i18n/t lang :admin/language)])
                 [:th {:style th}
                  (i18n/t lang :admin/major)]
                 [:th {:style th}
                  (i18n/t lang :admin/versions)]
                 [:th {:style th}
                  (i18n/t lang :admin/latest)]
                 [:th {:style th}]]]
               (into [:tbody]
                     (for [t shown]
                       ^{:key (str (:type t) "/" (:name t) "/" (:lang t) "/" (:major t))}
                       [:tr
                        [:td {:style td}
                         [:span {:style {:font-size "0.7em"
                                         :font-weight 700
                                         :letter-spacing "0.04em"
                                         :text-transform "uppercase"
                                         :color "#fff"
                                         :background
                                         (if (= "article" (:type t)) "#8a8175" "#2c5aa0")
                                         :padding "0.15em 0.5em"
                                         :border-radius "0.25em"}}
                          (:type t)]]
                        [:td {:style (assoc td :font-weight 600)}
                         [cite/node-link t (:name t)]]
                        (when all?
                          [:td {:style td}
                           (str/upper-case (:lang t))])
                        [:td {:style td}
                         (:major t)]
                        [:td {:style td}
                         (:versions t)]
                        [:td {:style td}
                         (str "v" (:major t) "." (:latest t))]
                        [:td {:style (assoc td :text-align "right")}
                         [admin-actions lang confirm t]]]))])])))
