(ns landing.agora.frontend.document-page
  "The shared Agora document engine — everything common to every document type, with **no
  knowledge of any specific type**. It exposes *features*, not type behaviour: whatever
  varies is either derived from a document's own data (a URL from its `:type`, a badge from
  whether it has a `:kind`) or taken as a parameter. The thin per-type facades (`ki-page`,
  `article-page`) choose those parameters and are what `core` calls; the generic page/form
  components they build on live in `view`/`edit`.

  Holds: the graph layout (`node-frame` with input/successor mini-cards and connectors),
  the badges (`kind-badge`/`lang-badge`/`doc-badge`), `byline`, `version-picker`, the
  language switcher `languages-control` and the whole translate flow (`translation-editor`
  + `::translate-*` events), `discover-card`/`add-card`/`fab`, `input-drop-fn`, the JSON
  request helper `json-req`, the auth `gated` helper, and the app chrome (header, footer,
  search, loading skeletons)."
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

(defn byline
  "The document's authorship line: author on the left, date pushed to the right. The author
  (copper) links to their profile page when `author-id` (the owning account) is given —
  seeded/unowned documents have no id and render as plain text."
  ([author published-at] (byline author published-at nil))
  ([author published-at author-id]
   (let [lang @(rf/subscribe [::i18n/lang])]
     [:div {:style {:color "#888"
                    :font-size "0.8em"
                    :margin-bottom "0.7em"
                    :display "flex"
                    :align-items "baseline"
                    :gap "0.5em"}}
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
            author])])
      [:span {:style {:margin-left "auto"
                      :white-space "nowrap"}}
       (or (fmt/utc published-at) "—")]])))

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
         ;; the node's text (unified) seeds the translation; the sibling permalink is built
         ;; generically from this node's own `:type` (no type branching here)
         source-text (cite/node-text node)
         lang-href (fn [l entry] (i18n/doc-permalink l type entry))
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
                                           (rf/dispatch [::translate-open {:doc-id id
                                                                           :type type
                                                                           :source-lang ki-lang
                                                                           :target-lang l
                                                                           :source-text source-text
                                                                           :source-title
                                                                           ki-title}]))
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
                :type type
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
                                       (str "/agora/api/" type "/" doc-id "/translate")
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
                        :dispatch [:agora/goto (i18n/doc-url target-lang type id)]}
                       {:db (assoc-in db [::translate-form :saving?] false)}))))

;; ---- Create a new KI (standalone form, #34) ----

(def version-tag-style
  {:color "#aaa"
   :font-size "0.72em"
   :font-family "monospace"})

(defn version-tag
  "The `vMAJOR.MINOR` badge. Shown to admins only for now — versioning is a power feature; a
  user option to reveal it will follow. Renders nothing for everyone else."
  [major minor]
  (when @(rf/subscribe [::auth/admin?])
    [:span {:style version-tag-style}
     (str "v" major "." minor)]))

(defn permalink
  "The public permanent URL (name + major) of a document ref in language `lang`, built
  from the ref's own `:type` — so it works for any document type."
  [lang doc]
  (i18n/doc-permalink lang (:type doc) doc))

;; ---- Full-text search box — searches name + statement, links to pages ----

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

(defn version-picker
  "Current version; clicking reveals an in-order strip of every version. `link-fn`
  builds a version's href from its id (type-specific — KI or article)."
  [{:keys [major minor versions]} link-fn]
  (r/with-let
   [open? (r/atom false)]
   (let [lang @(rf/subscribe [::i18n/lang])]
     (when @(rf/subscribe [::auth/admin?])
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
                      :let [current? (= (:minor v) minor)
                            draft? (:draft v)]]
                  ^{:key (:id v)}
                  ;; a draft version is shown but visually distinct — dashed border, italic, a ✎ mark
                  [:a {:href (link-fn (:id v))
                       :on-click #(reset! open? false)
                       :title (when draft? (i18n/t lang :ki/draft))
                       :style {:flex "0 0 auto"
                               :text-decoration "none"
                               :padding "0.1em 0.5em"
                               :border-radius "0.3em"
                               :border (str "1px "
                                            (if draft? "dashed " "solid ")
                                            (if current? "#b9770e" "#ddd"))
                               :font-style (if draft? "italic" "normal")
                               :background (if current? "#b9770e" "#fff")
                               :color (if current? "#fff" (if draft? "#b98a3e" "#b9770e"))}}
                   (str "v" major "." (:minor v) (when draft? " ✎"))])))]))))

(defn- mini-card
  "A compact neighbour card linking to `link`. When `on-drop` is given, a ✕
  removes the link (used for input links when editing)."
  [{c-title :title
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
     [version-tag major minor]]
    [:div {:style {:font-weight 600
                   :font-size "0.9em"}}
     c-title]]
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

(defn doc-badge
  "A document's badge — a feature of the *document*, not its type, so there is no type
  branching: a document that carries an epistemic `:kind` shows its coloured kind badge
  (optionally `:link?`ed to its definition); a document without a kind shows a neutral grey
  chip labelled from its own `:type` (the `:type/<type>` i18n key)."
  ([doc] (doc-badge doc nil))
  ([{:keys [type kind]} {:keys [link?]}]
   (if kind
     [kind-badge kind {:link? link?}]
     [:span {:style {:font-size "0.62em"
                     :font-weight 700
                     :letter-spacing "0.05em"
                     :text-transform "uppercase"
                     :color "#fff"
                     :background "#8a8175"
                     :padding "0.2em 0.6em"
                     :border-radius "0.25em"}}
      (i18n/t @(rf/subscribe [::i18n/lang]) (keyword "type" type))])))

(defn discover-card
  "A preview card for a document in a discover grid: its badge (a kind badge, or a neutral
  type chip), version, title, an excerpt of its text (citations flattened, clamped so the
  grid stays even), and the publication date."
  [lang node]
  ;; prepend the kind-guided opening (derived, not stored) so the card reads as the full
  ;; statement ("Sun Tzŭ holds that …") — nil for the free-form `inference` kind. Citations
  ;; are flattened to the cited KI's title (`:cite-titles`, since names are opaque cids).
  (let [titles (into {} (map (juxt :name :title)) (:cite-titles node))
        ;; prefix in the card's CONTENT language (`:lang node`), not the reader's interface lang
        excerpt (str (document-domain/statement-prefix-of node (:lang node))
                     (cite/plain-text (cite/node-text node) titles))]
    [:a {;; a draft doesn't resolve by permalink — link it by its exact-version URL
         :href (if (:draft node) (i18n/doc-url lang (:type node) (:id node)) (permalink lang node))
         :style {:display "flex"
                 :flex-direction "column"
                 :gap "0.55em"
                 :min-height "11em"
                 :padding "0.9em 1em"
                 :border (str "1px " (if (:draft node) "dashed #b98a3e" "solid #e2ddd2"))
                 :border-radius "0.6em"
                 :text-decoration "none"
                 :color "inherit"
                 :background "#fff"
                 :box-shadow "0 1px 3px rgba(0,0,0,0.06)"}}
     [:div {:style {:display "flex"
                    :align-items "center"
                    :gap "0.5em"}}
      [doc-badge node]
      [version-tag (:major node) (:minor node)]
      (when (:draft node)
        [:span {:style {:font-size "0.62em"
                        :font-weight 700
                        :text-transform "uppercase"
                        :letter-spacing "0.05em"
                        :color "#8a5709"
                        :background "#fdf6ec"
                        :border "1px dashed #b98a3e"
                        :padding "0.1em 0.45em"
                        :border-radius "0.25em"}}
         (str "✎ " (i18n/t lang :ki/draft-badge))])]
     [:div {:style {:font-weight 700
                    :font-size "1.02em"
                    :line-height 1.25
                    :color "#2a2723"}}
      (:title node)]
     [:div {:style {:font-size "0.9em"
                    :line-height 1.4
                    :color "#555"
                    :white-space "pre-wrap"
                    :display "-webkit-box"
                    :-webkit-line-clamp 5
                    :-webkit-box-orient "vertical"
                    :overflow "hidden"}}
      excerpt]
     ;; author and date on one line: author left, compact date pushed to the right
     [:div {:style {:margin-top "auto"
                    :color "#888"
                    :font-size "0.8em"
                    :display "flex"
                    :align-items "baseline"
                    :gap "0.5em"}}
      (let [src-author (or (:quote-author-name node) (:author-name (:source node)))]
        (if src-author
          ;; the KI quotes a source → attribute the cited author
          [:span {:title (i18n/t lang :card/quotes)}
           "📖 "
           [:span {:style {:color "#b9770e"
                           :font-weight 600}}
            src-author]]
          ;; no source → the byline author is this document's own (original) author
          (when-let [a (:author node)]
            [:span
             [:span {:style {:font-style "italic"}}
              (str (i18n/t lang :card/by) " ")]
             [:span {:style {:color "#b9770e"
                             :font-weight 600}}
              a]])))
      ;; compact date (Today / Yesterday / DD/MM / DD/MM/YYYY)
      [:span {:style {:margin-left "auto"
                      :white-space "nowrap"}}
       (or (fmt/short-date (:published-at node)
                           (i18n/t lang :date/today)
                           (i18n/t lang :date/yesterday))
           "—")]]]))

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

(defn discover-grid
  "A responsive discover grid of preview cards for `:items`, ending with a `+` add-card and
  a mobile FAB pointing at `(new-href-fn lang)`. `:heading-key` is optional (omitted when
  nil); `:tagline-key` sits above the grid; `:new-label-key` labels the add-card/FAB. When
  `:type` (\"ki\"/\"article\") is given, a **show-drafts** toggle re-fetches the feed including
  unpublished drafts (hidden by default), marked on their cards. Generic over document type —
  each per-type facade supplies the i18n keys + create route."
  [{:keys [heading-key tagline-key items new-href-fn new-label-key type]}]
  (r/with-let
   [drafts? (r/atom false) draft-items (r/atom nil)]
   (let [lang @(rf/subscribe [::i18n/lang])
         new-href (new-href-fn lang)
         new-label (i18n/t lang new-label-key)
         shown (if @drafts? (or @draft-items items) items)
         toggle! (fn []
                   (swap! drafts? not)
                   (when (and @drafts? type)
                     (-> (js/fetch (str "/agora/api/" type "?lang=" lang "&drafts=1")
                                   #js {:headers #js {"Accept" "application/json"}})
                         (.then #(.json %))
                         (.then #(reset! draft-items (js->clj % :keywordize-keys true)))
                         (.catch (fn [_])))))]
     [:div {:style {:max-width "72em"
                    :margin "1.5em auto"
                    :padding "0 0.8em"
                    :font-family "system-ui, sans-serif"}}
      ;; header row: heading (optional) left, a top 'create' button right — so the
      ;; create action is reachable without scrolling to the trailing add-card.
      [:div {:style {:display "flex"
                     :align-items "center"
                     :gap "0.8em"
                     :flex-wrap "wrap"
                     :margin-bottom "0.2em"}}
       (when heading-key
         [:h1 {:style {:font-size "1.4em"
                       :margin 0
                       :color "#1b1a17"}}
          (i18n/t lang heading-key)])
       [:a {:href new-href
            :title new-label
            :style {:margin-left "auto"
                    :display "inline-flex"
                    :align-items "center"
                    :gap "0.3em"
                    :padding "0.5em 1em"
                    :background "#b9770e"
                    :color "#fff"
                    :border-radius "0.4em"
                    :font-size "0.9em"
                    :font-weight 600
                    :white-space "nowrap"
                    :text-decoration "none"}}
        (str "＋ " new-label)]]
      [:p {:style {:color "#666"
                   :margin "0 0 0.6em"}}
       (i18n/t lang tagline-key)]
      (when type
        [:label {:style {:display "inline-flex"
                         :align-items "center"
                         :gap "0.35em"
                         :font-size "0.85em"
                         :color "#8a5709"
                         :margin "0 0 0.9em"
                         :cursor "pointer"}}
         [:input {:type "checkbox"
                  :checked @drafts?
                  :on-change toggle!}]
         (i18n/t lang :discover/show-drafts)])
      (into [:div {:style {:display "grid"
                           :grid-template-columns "repeat(auto-fill, minmax(min(17em, 100%), 1fr))"
                           :gap "0.9em"}}]
            (conj (mapv (fn [it] ^{:key (:id it)} [discover-card lang it]) shown)
                  ^{:key "__add__"} [add-card new-href new-label]))
      [fab new-href new-label]])))

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

