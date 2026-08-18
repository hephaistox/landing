(ns landing.agora.frontend.document-page
  "The shared Agora document engine — everything common to every document type, with **no
  knowledge of any specific type**. It exposes *features*, not type behaviour: whatever
  varies is either derived from a document's own data (a URL from its `:type`, a badge from
  whether it has a `:kind`) or taken as a parameter. The thin per-type facades (`ki-page`,
  `article-page`) choose those parameters and are what `core` calls; the generic page/form
  components they build on live in `view`/`edit`.

  Holds: the graph layout (`node-frame` with input/successor mini-cards and connectors),
  the badges (`kind-badge`/`lang-badge`/`doc-badge`), `byline`, `version-picker`, the
  language switcher `languages-control`, `discover-card`, `input-drop-fn`, the JSON
  request helper `json-req`, the auth `gated` helper, and the app chrome (header, footer,
  search, loading skeletons)."
  (:require
   [clojure.string                    :as str]
   [landing.agora.date                :as adate]
   [landing.agora.document.identity   :as di]
   [landing.agora.document.kind       :as dk]
   [landing.agora.frontend.auth       :as auth]
   [landing.agora.frontend.cite       :as cite]
   [landing.agora.frontend.fmt        :as fmt]
   [landing.agora.frontend.i18n       :as i18n]
   [landing.agora.frontend.ui-commons :as ui]
   [landing.language                  :as language]
   [re-frame.core                     :as rf]
   [reagent.core                      :as r]
   [superstructor.re-frame.fetch-fx]))

;; `error-flag` (the error bell, defined lower with the other error components) is used by the
;; neighbour `mini-card` above it — forward-declared to avoid a use-before-def warning.
(declare error-flag)

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
   (when kind
     (let [lang @(rf/subscribe [::i18n/lang])
           style {:display "inline-block"
                  :background (dk/kind-color kind "#666")
                  :color "#fff"
                  :font-size "0.7em"
                  :font-weight 700
                  :letter-spacing "0.05em"
                  :text-transform "uppercase"
                  :padding "0.2em 0.6em"
                  :border-radius "0.25em"}
           label (i18n/t lang (keyword "kind" (name kind)))
           pill [:span {:style style}
                 label]]
       (if-let [def-ref (when link? (get dk/kind-def kind))]
         ;; the kind ↔ definition-KI link is domain data (name + major); type is `ki`,
         ;; minor resolves to latest via by-major, lang is the reader's. `kind-def` carries the
         ;; keyword `:ki` (domain form); node identities on the client use the string type, so coerce.
         [cite/node-link
          (-> def-ref
              (assoc :lang lang)
              (update :type name))
          pill]
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

(defn tooltip
  "Show `text` in a small dark bubble above `child` after ~1s of hover — React-driven, so it is
  consistent across browsers (the native `title` delay varies wildly)."
  [text child]
  (r/with-let [show? (r/atom false) timer (r/atom nil)]
              [:span {:on-mouse-enter #(reset! timer (js/setTimeout (fn [] (reset! show? true))
                                                                    1000))
                      :on-mouse-leave #(do (some-> @timer
                                                   js/clearTimeout)
                                           (reset! show? false))
                      :style {:position "relative"
                              :display "inline-flex"}}
               child
               (when @show?
                 [:span {:style {:position "absolute"
                                 :bottom "calc(100% + 0.45em)"
                                 :left "50%"
                                 :transform "translateX(-50%)"
                                 :background "#1b1a17"
                                 :color "#fff"
                                 :font-size "0.72em"
                                 :font-weight 400
                                 :text-transform "none"
                                 :letter-spacing "normal"
                                 :line-height 1.35
                                 :padding "0.45em 0.65em"
                                 :border-radius "0.35em"
                                 :width "max-content"
                                 :max-width "17em"
                                 :text-align "center"
                                 :box-shadow "0 3px 10px rgba(0,0,0,0.3)"
                                 :z-index 60
                                 :pointer-events "none"}}
                  text])]
              (finally (some-> @timer
                               js/clearTimeout))))

(defn byline
  "The document's authorship line: author on the left, date pushed to the right. The author
  (copper) links to their profile page when `author-id` (the owning account) is given —
  seeded/unowned documents have no id and render as plain text. `date-hint`, when given, is a hover
  tooltip on the date saying what it is."
  ([author published-at] (byline author published-at nil nil))
  ([author published-at author-id] (byline author published-at author-id nil))
  ([author published-at author-id date-hint]
   (let [lang @(rf/subscribe [::i18n/lang])
         you? (and author-id (= author-id (:id @(rf/subscribe [::auth/user]))))
         date (or (fmt/utc published-at) "—")]
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
            author])
         ;; mark the current user's own authorship
         (when you?
           [:span {:style {:color "#999"}}
            (str " " (i18n/t lang :byline/you))])])
      [:span {:style {:margin-left "auto"
                      :white-space "nowrap"}}
       (if date-hint
         [tooltip
          date-hint
          [:span {:style {:cursor "help"}}
           date]]
         date)]])))

(defn publication-icon
  "A 📖 book icon (the publication glyph) linking to the publication a document belongs to — its
  provenance work-package. Hovering shows the publication title (native `title`); clicking opens it.
  Nothing when the document has no publication."
  [lang publication]
  (when-let [{:keys [id title]} publication]
    [:a {:href (i18n/publication lang id)
         :title title
         :style {:text-decoration "none"
                 :font-size "1.15em"
                 :line-height 1
                 :cursor "pointer"}}
     "📖"]))

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
  are the current one (marked) and the others (links). With nothing to switch to,
  it is just the badge. `ui-lang` is the interface language; `ki` supplies this
  KI's lang/name/major and its `:translations`."
  [ui-lang
   {ki-lang :lang
    ki-name :name
    :keys [type major translations]}]
  (r/with-let
   [open? (r/atom false)]
   (let [;; the sibling permalink is built generically from this node's own `:type`
         lang-href (fn [l entry] (i18n/doc-permalink l type entry))
         present (into {ki-lang {:lang ki-lang
                                 :name ki-name
                                 :major major
                                 :current? true}}
                       (map (juxt :lang identity) translations))
         openable? (seq translations)]
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
          (into [:div {:style {:display "flex"
                               :flex-direction "column"
                               :gap "0.15em"}}]
                (for [l language/languages
                      :let [entry (present l)]
                      ;; only existing versions are listed — switching is a link
                      :when entry]
                  ^{:key l}
                  (if (:current? entry)
                    [:div {:style {:padding "0.4em 0.5em"
                                   :border-radius "0.3em"
                                   :background "#f4efe4"
                                   :font-weight 700
                                   :color "#7a5209"}}
                     (get language/language-name l l)]
                    [:a {:href (lang-href l entry)
                         :on-click #(reset! open? false)
                         :style {:padding "0.4em 0.5em"
                                 :border-radius "0.3em"
                                 :text-decoration "none"
                                 :color "#b9770e"}}
                     (get language/language-name l l)])))]])])))

(defn json-req
  "A JSON `:fetch` request map. `on-failure` is explicit so the helper is decoupled
  from any one screen's error handler (the edit/create flows pass `[::op-failed]`)."
  [method url body on-success on-failure]
  {:method method
   :url url
   :headers {"Content-Type" "application/json"
             "Accept" "application/json"}
   :body (js/JSON.stringify (clj->js body))
   :response-content-types {#"application/json" :json}
   :on-success on-success
   :on-failure on-failure})

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
  "Current version; clicking reveals an in-order strip of every version. The version list is
  admin-only and fetched lazily on open (keyed by the current version `id`), so a normal read never
  loads it. `link-fn` builds a version's href from its id (type-specific — KI or article)."
  [{:keys [type id major minor]} link-fn]
  (r/with-let
   [open? (r/atom false)]
   (let [lang @(rf/subscribe [::i18n/lang])]
     (when @(rf/subscribe [::auth/admin?])
       (let [versions @(rf/subscribe [:agora/versions id])]
         [:span {:style {:display "inline-flex"
                         :align-items "center"
                         :gap "0.4em"
                         :font-family "monospace"
                         :font-size "0.8em"}}
          [:button {:on-click (fn [_]
                                (swap! open? not)
                                (when @open? (rf/dispatch [:agora/ensure-versions type id])))
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
                     (str "v" major "." (:minor v) (when draft? " ✎"))])))])))))

(defn- mini-card
  "A compact neighbour card linking to `link`. When `on-drop` is given, a ✕
  removes the link (used for input links when editing)."
  [{c-title :title
    c-type :kind
    :keys [major minor errors]}
   link
   on-drop
   locator]
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
     [version-tag major minor]
     ;; the error bell, pushed to the row's end (the neighbour's own errors, loaded with it)
     [:span {:style {:margin-left "auto"}}
      [error-flag (count errors)]]]
    [:div {:style {:font-weight 600
                   :font-size "0.9em"}}
     c-title]
    ;; a cited source carries a locator (page / verse / entry); shown under the title
    (when (seq locator)
      [:div {:style {:font-size "0.78em"
                     :color "#8a7a55"
                     :margin-top "0.2em"}}
       locator])]
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
  (fn [id {:keys [link-fn drop-fn locator]}]
    (if-let [doc @(rf/subscribe [:agora/doc id])]
      [mini-card doc (link-fn doc) (when drop-fn (drop-fn doc)) locator]
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
            :drop-fn drop-fn
            :locator (:locator n)}])))

(defn input-drop-fn
  "For an editable node page: a fn of an input's loaded doc → the ✕ on-click that drops
  that input link (a new minor of `node-id`, type `type`) by stripping its `[[…]]`
  citation from the text. nil when the viewer isn't logged in — removal is an authoring
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
                      ;; the input's full TNLR — `strip-cite` matches the `[[…]]` token
                      ;; on type/name/lang/major
                      {:type (:type input-doc)
                       :name (:name input-doc)
                       :lang (:lang input-doc)
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
   (let [lang @(rf/subscribe [::i18n/lang])
         ins inputs]
     [:div {:style {:display "flex"
                    :flex-direction "column"
                    :align-items "center"
                    :padding "1em 0.6em 2em"}}
      (when (seq ins) [:<> [neighbours-row ins link-fn input-drop?] [connector]])
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

(defn- card-href
  "The link a discover card points at, from the node's own `:type`: a publication opens its page; a
  draft document resolves only by its exact-version URL; anything else by its public permalink."
  [lang node]
  (cond
    (= "publication" (name (:type node))) (i18n/publication lang (:id node))
    (:draft node) (i18n/doc-url lang (:type node) (:id node))
    :else (permalink lang node)))

(defn card-status-badge
  "A small labelled chip for a node that carries a `:status` (a publication — open / closed), the same
  role a `kind`/`type` badge plays for a document. Renders nothing without a `:status`."
  [lang status]
  (when status
    (let [closed? (= "closed" (name status))]
      [:span {:title (i18n/t lang (if closed? :pub/status-closed-hint :pub/status-open-hint))
              :style {:font-size "0.62em"
                      :font-weight 700
                      :letter-spacing "0.05em"
                      :text-transform "uppercase"
                      :padding "0.2em 0.6em"
                      :border-radius "0.25em"
                      :color (if closed? "#1d6b2f" "#8a5a00")
                      :background (if closed? "#dff3e2" "#fff3d6")}}
       (i18n/t lang (if closed? :pub/status-closed :pub/status-open))])))

;; --- document errors (see engine/document-errors): a bell flags them on every card; the specific,
;; clickable list lives on the flagged document's own read view.

(defn error-flag
  "A small bell flagging a document with `n` problems (nothing when 0/nil). A non-interactive
  indicator — cards and mini-cards are links, so the detail lives on the flagged document's own page
  (`error-panel` there), one click away."
  [n]
  (when (pos? (or n 0))
    (let [lang @(rf/subscribe [::i18n/lang])]
      [:span {:title (i18n/t lang :error/flag-title)
              :style {:display "inline-flex"
                      :align-items "center"
                      :gap "0.1em"
                      :color "#c92a2a"
                      :font-size "0.9em"}}
       "🔔"
       (when (> n 1)
         [:span {:style {:font-size "0.72em"
                         :font-weight 700}}
          n])])))

(defn- error-message
  "One document error as a specific, clickable line — never a generic sentence. `:stale-ref` names the
  referenced document and links to its up-to-date version; `:missing-inputs` names the kind that must
  have a predecessor. `err` comes from JSON, so `:error`/`:doc-kind` are coerced to keywords."
  [lang err]
  (case (some-> (:error err)
                keyword)
    :stale-ref (let [{:keys [ref current]} err]
                 [:span
                  "« "
                  [:strong (:title ref)]
                  " » "
                  (i18n/t lang :error/stale-ref)
                  " — "
                  [:a {:href (i18n/doc-url lang (name (or (:type ref) "ki")) current)
                       :style {:color "#b9770e"
                               :font-weight 600}}
                   (i18n/t lang :error/stale-ref-link)]])
    :missing-inputs [:span
                     [:strong (i18n/t lang (keyword "kind" (name (:doc-kind err))))]
                     " — "
                     (i18n/t lang :error/missing-inputs)]
    [:span (str (:error err))]))

(defn error-panel
  "The specific, clickable list of a document's `errors`, shown on its own read view (where they get
  fixed). Nothing when the document is sound."
  [errors]
  (when (seq errors)
    (let [lang @(rf/subscribe [::i18n/lang])]
      [:div {:style {:border "1px solid #f0c0c0"
                     :background "#fff6f6"
                     :border-radius "0.4em"
                     :padding "0.6em 0.8em"
                     :margin "0.6em 0"}}
       [:div {:style {:display "flex"
                      :align-items "center"
                      :gap "0.35em"
                      :color "#c92a2a"
                      :font-weight 700
                      :font-size "0.82em"
                      :margin-bottom "0.35em"}}
        (str "🔔 " (i18n/t lang :error/panel-title))]
       (into [:ul {:style {:margin 0
                           :padding-left "1.2em"
                           :font-size "0.85em"
                           :color "#5a2a2a"
                           :line-height 1.6}}]
             (for [[i err] (map-indexed vector errors)]
               ^{:key i} [:li [error-message lang err]]))])))

(defn provenance-line
  "The compact provenance line — 'Écrit par <author> dans <publication>, le <date>'. Author and
  publication are links on the read page, but plain text with `:links? false` — used inside the
  discover card, which is itself an `<a>` (a nested `<a>` is invalid HTML). `:pub? false` drops the
  publication, keeping author and date only."
  ([lang node] (provenance-line lang node nil))
  ([lang
    node
    {:keys [links? pub?]
     :or {links? true
          pub? true}}]
   (let [author (or (:attributed-author node) (:author node))
         author-id (or (:attributed-author-id node) (:author-id node))
         pub (:publication node)
         ;; reads « le 03/08 » for an absolute date but « Aujourd'hui » (no connector) for a relative
         ;; one — the connector is added only where it fits
         date (adate/labelled-date (:published-at node)
                                   {:today (i18n/t lang :date/today)
                                    :yesterday (i18n/t lang :date/yesterday)
                                    :on (i18n/t lang :card/on-date)})]
     [:div {:style {:color "#888"
                    :font-size "0.8em"
                    :line-height 1.5}}
      (when author
        [:<>
         (str (i18n/t lang :card/by) " ")
         (if (and author-id links?)
           [:a {:href (i18n/author lang author-id)
                :style {:color "#b9770e"
                        :font-weight 600
                        :text-decoration "none"}}
            author]
           [:span {:style {:color (if links? "#b9770e" "#666")
                           :font-weight 600}}
            author])])
      (when (and pub? pub)
        [:<>
         (str " " (i18n/t lang :card/in-pub) " ")
         (if links?
           [:a {:href (i18n/publication lang (:id pub))
                :style {:color "#b9770e"
                        :text-decoration "none"}}
            (:title pub)]
           [:span {:style {:color "#666"}}
            (:title pub)])])
      (when date (str ", " date))])))

(defn discover-card
  "A preview card for a node in a discover grid: its badge (a kind badge, a status chip for a
  publication, or a neutral type chip), version, title, an excerpt of its text (when it has any,
  citations flattened and clamped so the grid stays even), and an author/date footer. Field-
  driven, so KIs, articles and publications all render through it."
  [lang node]
  ;; prepend the kind-guided opening (derived, not stored) so the card reads as the full
  ;; statement ("Sun Tzŭ holds that …") — nil for the free-form `inference` kind. Citations
  ;; are flattened to the cited KI's title (`:cite-titles`, since names are opaque cids).
  (let [text (cite/node-text node)
        ;; prefix in the card's CONTENT language (`:lang node`), not the reader's interface lang
        excerpt (when (seq text)
                  (str (dk/statement-prefix-of node (keyword (:lang node)))
                       (di/plain-text text (:cite-titles node))))]
    [:a {:href (card-href lang node)
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
      [card-status-badge lang (:status node)]
      (when (:major node) [version-tag (:major node) (:minor node)])
      ;; draft is already shown by the card's dashed border — no separate badge
      ;; the error bell, pushed to the row's end
      [:span {:style {:margin-left "auto"}}
       [error-flag (count (:errors node))]]]
     [:div {:style {:font-weight 700
                    :font-size "1.02em"
                    :line-height 1.25
                    :color "#2a2723"}}
      (:title node)]
     (when excerpt
       [:div {:style {:font-size "0.9em"
                      :line-height 1.4
                      :color "#555"
                      :white-space "pre-wrap"
                      :display "-webkit-box"
                      :-webkit-line-clamp 5
                      :-webkit-box-orient "vertical"
                      :overflow "hidden"}}
        excerpt])
     ;; compact provenance, pinned to the card's bottom — author and date only, the publication is
     ;; too heavy for a card
     [:div {:style {:margin-top "auto"}}
      [provenance-line
       lang
       node
       {:links? false
        :pub? false}]]]))

;; --- shared browse filter (scope / lang / author / q) — narrows a grid client-side, like the author
;; page. `:scope` and `:lang` are global (browsing preferences); the text filters (`:author`/`:q`) are
;; kept **per view** (`:text {view-kind {…}}`), so switching KI ↔ article ↔ publication and back
;; restores each view's own search rather than clearing it. `:lang` nil = the interface language (the
;; default), `:all` = every language, else a concrete content language.
(rf/reg-sub ::browse-filter
            (fn [db _]
              (let [f (:agora/browse-filter db)]
                (merge {:scope (:scope f :all)
                        :lang (:lang f)}
                       (get-in f [:text (:kind (:view db))])))))
(rf/reg-event-db ::set-filter
                 (fn [db [_ k v]]
                   (if (contains? #{:scope :lang} k)
                     (assoc-in db [:agora/browse-filter k] v)
                     (assoc-in db [:agora/browse-filter :text (:kind (:view db)) k] v))))

(defn- passes-filter?
  "True when `node` clears the browse `filter` {:scope :lang :author :q :kinds} for `viewer-id`: `:mine`
  keeps only the viewer's own; `:lang` (a concrete language, nil = any) the node's content language;
  `:author` a name substring on the byline; `:q` a substring of title/text/author; `:kinds` (a set,
  empty = any) the node's kind must be in. `:lang` is already resolved (see `filter-items`)."
  [viewer-id {:keys [scope lang author q kinds]} node]
  (let [owner (or (:attributed-author-id node) (:author-id node))
        ;; match what the card shows as the byline
        author-name (or (:attributed-author node) (:author node) "")
        ;; search the prose as the card shows it — citations read as the titles they reference, so
        ;; typing a cited title matches and an opaque cid never does
        hay (str/lower-case (str (:title node)
                                 " " (di/plain-text (:text node) (:cite-titles node))
                                 " " author-name))]
    (and (or (not= scope :mine) (and viewer-id (= viewer-id owner)))
         (or (nil? lang)
             ;; a language-neutral container (a publication, lang `zz`) is exempt from a content-
             ;; language filter — it has no real language to match
             (contains? #{nil "zz"}
                        (some-> (:lang node)
                                name))
             (= (name lang)
                (some-> (:lang node)
                        name)))
         (or (str/blank? author)
             (str/includes? (str/lower-case author-name) (str/lower-case author)))
         (or (str/blank? q) (str/includes? hay (str/lower-case q)))
         (or (empty? kinds)
             (contains? kinds
                        (some-> (:kind node)
                                keyword))))))

(defn- effective-lang
  "Resolve the browse filter's `:lang` to the concrete content language to keep, or nil for every
  language: nil (unset) falls back to the interface language `ui-lang`, `:all` means no language
  restriction, anything else is that language verbatim."
  [filter-lang ui-lang]
  (case filter-lang
    nil ui-lang
    :all nil
    filter-lang))

(defn filter-items
  "Narrow `items` by the current browse filter for the signed-in viewer — the client-side counterpart
  of the shared `filter-bar`. The `:lang` preference is resolved against the interface language here."
  [items]
  (let [f @(rf/subscribe [::browse-filter])
        viewer-id (:id @(rf/subscribe [::auth/user]))
        f (assoc f :lang (effective-lang (:lang f) @(rf/subscribe [::i18n/lang])))]
    (filterv #(passes-filter? viewer-id f %) items)))

(defn- seg-style
  "The shared pill-button style for a filter control — copper outline, filled when `on?`."
  [on?]
  {:border "1px solid #b9770e"
   :background (if on? "#b9770e" "#fff")
   :color (if on? "#fff" "#b9770e")
   :border-radius "0.35em"
   :padding "0.28em 0.7em"
   :font-size "0.82em"
   :font-weight 600
   :cursor "pointer"})

(defn filter-dropdown
  "An Excel-style filter combobox: a labelled button (highlighted when `active?`, with an optional
  `summary` of the current choice) that opens an anchored panel of `body`. Closes on an outside click
  (a transparent full-screen backdrop) or by re-clicking the button. Local open state."
  [{:keys [label summary active?]} body]
  (r/with-let
   [open? (r/atom false)]
   [:div {:style {:position "relative"
                  :display "inline-block"}}
    [:button {:on-click #(swap! open? not)
              :style (merge (seg-style active?)
                            {:display "inline-flex"
                             :align-items "center"
                             :gap "0.4em"})}
     [:span
      label
      (when summary
        [:span {:style {:font-weight 400
                        :opacity 0.85}}
         (str " · " summary)])]
     [:span {:style {:font-size "0.72em"}}
      "▾"]]
    (when @open?
      [:<>
       [:div {:on-click #(reset! open? false)
              :style {:position "fixed"
                      :inset 0
                      :z-index 40}}]
       [:div {:style {:position "absolute"
                      :top "calc(100% + 0.3em)"
                      :left 0
                      :z-index 41
                      :min-width "13em"
                      :background "#fff"
                      :border "1px solid #d9c9a3"
                      :border-radius "0.45em"
                      :box-shadow "0 6px 18px rgba(0,0,0,0.14)"
                      :padding "0.6em"
                      :max-height "18em"
                      :overflow-y "auto"}}
        body]])]))

(defn check-row
  "A checkbox/radio option row inside a filter panel: a full-width clickable line, marked when `on?`."
  [on? label on-click]
  [:button {:on-click on-click
            :style {:display "flex"
                    :align-items "center"
                    :gap "0.5em"
                    :width "100%"
                    :text-align "left"
                    :border "none"
                    :background (if on? "#faf3e2" "transparent")
                    :color "#5a4a2a"
                    :border-radius "0.3em"
                    :padding "0.32em 0.5em"
                    :font-size "0.85em"
                    :cursor "pointer"}}
   [:span {:style {:color "#b9770e"}}
    (if on? "☑" "☐")]
   label])

(defn filter-bar
  "The shared browse filter bar as Excel-style comboboxes: an **Author** dropdown (a mine/all scope
  toggle + a name field), an optional **Type** dropdown (`kind-ids` — the kinds offered as checkboxes;
  nil/empty = hidden), an optional **Language** dropdown (`lang-ids` — the content languages present;
  nil/empty = hidden), and an always-visible text search. Drives `:agora/browse-filter`; `filter-items`
  applies it. `opts` may set `:author? false` to drop the Author control — e.g. an author page, where
  the author is already fixed. Used by every browse surface."
  ([lang kind-ids lang-ids] (filter-bar lang kind-ids lang-ids nil))
  ([lang kind-ids lang-ids opts]
   (let [{:keys [scope author q kinds]
          filter-lang :lang}
         @(rf/subscribe [::browse-filter])
         kinds (or kinds #{})
         author-control? (get opts :author? true)
         ;; the "mine" scope reads as the signed-in user's own name; falls back to a generic label when
         ;; logged out (nothing is "mine" then anyway)
         mine-label (or (not-empty (:display-name @(rf/subscribe [::auth/user])))
                        (i18n/t lang :filter/mine))
         author? (or (= scope :mine) (not (str/blank? author)))
         lang-summary (cond
                        (nil? filter-lang) (str/upper-case lang)
                        (= filter-lang :all) (i18n/t lang :filter/lang-all)
                        :else (str/upper-case (name filter-lang)))
         field (fn [k value ph extra] [ui/composed-field {:type "text"
                                                          :value (or value "")
                                                          :placeholder (i18n/t lang ph)
                                                          :on-text #(rf/dispatch [::set-filter k %])
                                                          :style (merge {:padding "0.35em 0.6em"
                                                                         :border "1px solid #ccc"
                                                                         :border-radius "0.35em"
                                                                         :font-size "0.85em"
                                                                         :min-width "9em"}
                                                                        extra)}])]
     [:div {:style {:display "flex"
                    :flex-wrap "wrap"
                    :gap "0.45em"
                    :align-items "center"
                    :margin "0 0 0.9em"}}
      (when author-control?
        [filter-dropdown {:label (i18n/t lang :filter/author)
                          :active? author?
                          :summary (cond
                                     (= scope :mine) mine-label
                                     (not (str/blank? author)) author)}
         [:div {:style {:display "flex"
                        :flex-direction "column"
                        :gap "0.4em"
                        :min-width "12em"}}
          ;; scope options as a vertical list, the user's own name and "all" at the same level; picking
          ;; "all" also clears any typed author name, so the two author controls never contradict
          [:div {:style {:display "flex"
                         :flex-direction "column"}}
           [check-row (= scope :mine) mine-label #(rf/dispatch [::set-filter :scope :mine])]
           [check-row
            (= scope :all)
            (i18n/t lang :filter/all)
            #(do (rf/dispatch [::set-filter :scope :all]) (rf/dispatch [::set-filter :author ""]))]]
          (field :author author :filter/author-ph nil)]])
      (when (seq kind-ids)
        [filter-dropdown {:label (i18n/t lang :filter/type)
                          :active? (seq kinds)
                          :summary (when (seq kinds) (count kinds))}
         (into [:div {:style {:display "flex"
                              :flex-direction "column"}}]
               (for [k kind-ids
                     :let [on? (contains? kinds k)]]
                 ^{:key k}
                 [check-row
                  on?
                  (i18n/t lang (keyword "kind" (name k)))
                  #(rf/dispatch [::set-filter :kinds (if on? (disj kinds k) (conj kinds k))])]))])
      (when (seq lang-ids)
        [filter-dropdown {:label (i18n/t lang :filter/lang)
                          :active? (some? filter-lang)
                          :summary lang-summary}
         (into [:div {:style {:display "flex"
                              :flex-direction "column"}}
                [check-row
                 (= filter-lang :all)
                 (i18n/t lang :filter/lang-all)
                 #(rf/dispatch [::set-filter :lang :all])]]
               (for [l lang-ids
                     :let [lk (keyword l)
                           ;; the interface language is the default (unset), so selecting it clears the
                           ;; explicit filter rather than pinning it
                           on? (or (= filter-lang lk) (and (nil? filter-lang) (= (name lk) lang)))]]
                 ^{:key l}
                 [check-row
                  on?
                  (str/upper-case (name l))
                  #(rf/dispatch [::set-filter :lang (if (= (name lk) lang) nil lk)])]))])
      ;; the search field grows to fill whatever the combos leave on the row
      (field :q q :filter/search-ph {:flex 1})])))

(defn card-grid
  "The responsive grid of discover cards for `items`. The shared browse layout — KIs, articles and
  publications lay out identically."
  [lang items]
  (into [:div {:style {:display "grid"
                       :grid-template-columns "repeat(auto-fill, minmax(min(17em, 100%), 1fr))"
                       :gap "0.9em"}}]
        (map (fn [it] ^{:key (:id it)} [discover-card lang it]) items)))

(defn discover-grid
  "A responsive discover grid of preview cards for `:items`. `:heading-key` is optional (omitted
  when nil). Generic over document type — each per-type facade supplies the i18n keys. Creating is
  not part of the grid: the floating create control is the one create affordance, on every screen."
  [{:keys [heading-key items]}]
  (let [lang @(rf/subscribe [::i18n/lang])
        ;; the kinds actually present in the feed, in canonical order — the Type filter's checkboxes
        kind-ids (filterv (into #{}
                                (keep #(some-> (:kind %)
                                               keyword))
                                items)
                          dk/kind-ids)
        ;; the content languages present in the feed, in canonical order — the Language filter's options
        lang-ids (filterv (into #{}
                                (keep #(some-> (:lang %)
                                               name))
                                items)
                          language/languages)]
    [:div {:style {:max-width "72em"
                   :margin "1.5em auto"
                   :padding "0 0.8em"
                   :font-family "system-ui, sans-serif"}}
     (when heading-key
       [:h1 {:style {:font-size "1.4em"
                     :margin "0 0 0.2em"
                     :color "#1b1a17"}}
        (i18n/t lang heading-key)])
     [filter-bar lang kind-ids lang-ids]
     [card-grid lang (filter-items items)]]))

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

