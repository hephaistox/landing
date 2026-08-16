(ns landing.agora.frontend.cite
  "Inline KI citations, shared by every node text (KI statement, article body).

  A `[[ki:<name>@<major>]]` (optionally `…|custom text]]`) token in a node's text
  cites a KI: it renders as a KI-marked inline link with a metadata hover card, and
  — because inputs are exactly the citations in the text — it *is* an input edge.
  The grammar lives once in `di/cite-pattern`, so the renderer here and the
  backend citation extractor never drift.

  This ns is deliberately low-level (no dependency on the `view`/`edit` page layers) so
  both read and authoring screens can use it; it renders its own small kind/lang badges to
  stay decoupled."
  (:require
   [clojure.string                    :as str]
   [landing.agora.document.identity   :as di]
   [landing.agora.document.kind       :as dk]
   [landing.agora.frontend.fmt        :as fmt]
   [landing.agora.frontend.i18n       :as i18n]
   [landing.agora.frontend.ui-commons :as ui]
   [re-frame.core                     :as rf]
   [reagent.core                      :as r]))

(defn humanize
  "A readable heading from a slug: `confidence-is-partial` → `Confidence is
  partial`. Used as a citation label until the KI's real title has loaded."
  [s]
  (let [t (-> (or s "")
              (str/replace #"[-_]+" " ")
              str/trim)]
    (if (str/blank? t) (str s) (str (str/upper-case (subs t 0 1)) (subs t 1)))))

(defn node-text "A document's prose (the unified `:text` key)." [doc] (:text doc))

(defn parse-segments
  "Split a paragraph into a vector of parts: plain strings and citation maps
  `{:name … :lang … :major … :text …}` (`:lang` nil unless the token carried one, `:text` nil
  unless it gave a custom label)."
  [s]
  (let [re (js/RegExp. (.-source di/cite-pattern) "g")]
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
                       {:type (aget m 1)
                        :name (aget m 2)
                        :lang (aget m 3)
                        :major (js/parseInt (aget m 4))
                        :text (aget m 5)})))
        (let [tail (subs s pos)]
          (cond-> out
            (seq tail) (conj tail)))))))

(defn- cite-link-style
  [kind]
  (let [c (dk/kind-color kind "#b9770e")]
    {:color c
     :text-decoration "none"
     :border-bottom (str "1px dotted " c)
     :cursor "pointer"
     :font-weight 600}))

(defn- mini-kind-badge
  [kind]
  (when kind
    [:span {:style {:font-size "0.62em"
                    :font-weight 700
                    :letter-spacing "0.04em"
                    :text-transform "uppercase"
                    :color "#fff"
                    :background (dk/kind-color kind "#666")
                    :padding "0.15em 0.45em"
                    :border-radius "0.25em"}}
     (i18n/t @(rf/subscribe [::i18n/lang]) (keyword "kind" (name kind)))]))

(defn- mini-lang-badge
  [lang]
  (when lang
    [:span {:style {:font-size "0.7em"
                    :font-weight 700
                    :letter-spacing "0.03em"
                    :color "#8a7a55"
                    :border "1px solid #d9c9a3"
                    :border-radius "0.25em"
                    :padding "0 0.35em"}}
     (str/upper-case lang)]))

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
    [mini-kind-badge (:kind doc)]]
   (when-let [s (node-text doc)]
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
    [mini-lang-badge (:lang doc)]
    (when-let [d (fmt/utc (:published-at doc))] [:span "· " d])
    (when-let [a (:attributed-author doc)] [:span "· " a])]])

(defn ki-cite
  "An inline living citation of a KI (`name + major`). Fetches the KI (by-major,
  latest minor) into the shared cache, renders its title as a KI-marked link, and
  shows the hover card on hover/focus."
  [seg]
  (let [{:keys [name major lang]} seg]
    (rf/dispatch [:agora/ensure-ki-by-major name major (or lang @(rf/subscribe [::i18n/lang]))]))
  (let [hover? (r/atom false)]
    (fn [{:keys [name major text lang]}]
      ;; the citation resolves to its own language when the token carried one, else the reader's
      (let [clang (or lang @(rf/subscribe [::i18n/lang]))
            doc @(rf/subscribe [:agora/cite-doc name major clang])]
        [:span {:style {:position "relative"}
                :on-mouse-enter #(reset! hover? true)
                :on-mouse-leave #(reset! hover? false)}
         [:a {:href (i18n/ki clang
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

(defn node-link
  "A link to any node (KI or article) that shows its metadata card on hover — the same
  hover UX as an inline citation, reused wherever we only hold a node's identity (e.g.
  the admin page). `node` is {:type :name :major :lang :title}; `label` overrides the
  link text (defaults to the node's title/slug). The node is fetched lazily on
  hover/focus, so a page full of these (the admin table) doesn't fetch everything up
  front.

  When `node` carries an `:id`, the link is **pinned to that exact version**: it points
  at the by-id URL and its hover preview fetches that version by id, so a former version
  shows *its own* content — not the latest minor. Without an `:id` it links to the
  living permalink (name + major → latest minor) and previews that."
  [_node _label]
  (let [hover? (r/atom false)
        fetched? (r/atom false)]
    (fn [{:keys [type name major lang id]
          :as node}
         label]
      (let [pinned? (boolean id)
            doc (if pinned?
                  @(rf/subscribe [:agora/node-doc-by-id type id])
                  @(rf/subscribe [:agora/node-doc type name major lang]))
            reveal (fn []
                     (reset! hover? true)
                     (when-not @fetched?
                       (reset! fetched? true)
                       (if pinned?
                         (rf/dispatch [:agora/ensure-by-id type id])
                         (rf/dispatch [:agora/ensure-by-major type name major lang]))))]
        [:span {:style {:position "relative"}
                :on-mouse-enter reveal
                :on-mouse-leave #(reset! hover? false)}
         [:a {:href (cond
                      pinned? (if (= type "article") (i18n/article lang id) (i18n/ki-id lang id))
                      (= type "article") (i18n/article-permalink lang node)
                      :else (i18n/ki lang node))
              :on-focus reveal
              :on-blur #(reset! hover? false)
              :style {:color "#b9770e"
                      :font-weight 600
                      :text-decoration "none"}}
          (or label (:title doc) (:title node) (humanize name))]
         (when (and @hover? doc) [ki-hover-card doc])]))))

(defn- inline-segs
  "One prose line → a seq of hiccup children: plain strings and resolved `[ki-cite …]`s."
  [line]
  (map (fn [seg] (if (string? seg) seg [ki-cite seg])) (parse-segments line)))

(defn- para-block
  "A paragraph: its lines joined by `<br>`; `lead` (optional) spliced inline at the very start
  (so a boxed statement prefix flows into the prose)."
  [lines lead]
  (into [:p {:style {:margin "0 0 1em"}}]
        (concat (when lead [lead])
                (->> lines
                     (map (comp vec inline-segs))
                     (interpose [[:br]])
                     (apply concat)))))

(defn- ul-block
  "A bullet list; each item may carry inline citations."
  [items]
  (into [:ul {:style {:margin "0 0 1em"
                      :padding-left "1.4em"}}]
        (map-indexed (fn [i item]
                       (with-meta (into [:li {:style {:margin "0.2em 0"}}]
                                        (inline-segs item))
                                  {:key i}))
                     items)))

(defn render-text
  "Render a node's text (statement/body) as blocks — **paragraphs** (blank line separates; a
  single line-break becomes a `<br>`) and **bullet lists** (`- `/`* ` lines) — resolving inline
  `[[ki:…]]` citations to living KI links. `lead` (optional) is spliced inline at the start of
  the first paragraph (e.g. the kind-guided statement prefix as a boxed pill)."
  ([text] (render-text text nil))
  ([text lead]
   (let [blocks (dk/parse-blocks text)
         first-p? (= :p (:type (first blocks)))]
     (into [:div]
           (concat
            ;; lead with no paragraph to open (empty text, or a list first) → its own <p>
            (when (and lead (not first-p?))
              [(with-meta [:p {:style {:margin "0 0 1em"}}
                           lead]
                          {:key "lead"})])
            (map-indexed (fn [i blk]
                           (with-meta (if (= :ul (:type blk))
                                        (ul-block (:items blk))
                                        (para-block (:lines blk)
                                                    (when (and (zero? i) first-p?) lead)))
                                      {:key i}))
                         blocks))))))

(defn plain-text
  "`text` with its `[[ki:…]]` citations flattened to a label — for excerpts/previews where
  rendering live links would be too heavy (e.g. a discover grid of many cards). A citation's
  label is its custom text, else the cited KI's title from the optional `titles` map
  (`{cid → title}`), else a humanized slug. Since names are opaque cids, the `titles` map is
  what keeps a card excerpt readable."
  ([text] (plain-text text nil))
  ([text titles]
   (->> (parse-segments (or text ""))
        (map
         (fn [seg]
           (if (string? seg) seg (or (:text seg) (get titles (:name seg)) (humanize (:name seg))))))
        (apply str))))

(defn citations
  "The set of KIs cited in `text`, as {:name :major} — the node's declared inputs."
  [text]
  (->> (parse-segments text)
       (filter map?)
       (map #(select-keys % [:name :major]))
       set))

(defn without-citations
  "`text` with its `[[…]]` citation tokens removed, leaving the surrounding prose (trimmed). For an
  extract — whose one citation, its work, is shown as the statement prefix — this yields the verbatim
  quote alone, so the work is not rendered twice."
  [text]
  (str/trim (apply str (filter string? (parse-segments (or text ""))))))

;; ---------------------------------------------------------------------------
;; Editing: the text area + a search box that cites (existing) / creates (new) a KI
;; ---------------------------------------------------------------------------

(def ^:private result-btn-style
  {:display "flex"
   :align-items "center"
   :gap "0.5em"
   :width "100%"
   :text-align "left"
   :padding "0.5em 0.7em"
   :border "none"
   :border-bottom "1px solid #f0f0f0"
   :background "#fff"
   :cursor "pointer"
   :font-size "0.9em"})

(defn- kind-picker
  "The KI kinds as clickable badges (selected highlighted, rest dimmed) — for the inline
  new-KI form. Local to cite so the low-level editor stays free of the `edit` layer. The
  bibliographic kinds (`work`/`extract`) are excluded — they are authored through their own
  surfaces, not quick-created as a reasoning predecessor here."
  [selected on-select]
  (into [:div {:style {:display "flex"
                       :flex-wrap "wrap"
                       :gap "0.3em"}}]
        (for [k (->> (dk/kind-ids-of "ki")
                     (remove dk/kind-bibliographic?))]
          ^{:key k}
          [:button {:on-click #(on-select k)
                    :title (name k)
                    :style {:border "none"
                            :background "transparent"
                            :padding "0.1em"
                            :cursor "pointer"
                            :border-radius "0.3em"
                            :opacity (if (= k selected) 1 0.4)
                            :box-shadow (if (= k selected) "0 0 0 2px #333" "none")}}
           [mini-kind-badge k]])))

(defn citation-editor
  "A node-text editor: an auto-growing textarea plus a search box that **cites an
  existing KI** (click a result) or **creates a new KI** (the ＋ action) — either way
  splicing a `[[ki:name@major]]` token at the cursor. Since inputs are the text's
  citations, this is how you add an input. `value` is the text; `set-text!` is called
  with the new text on every edit and insert; `placeholder` is the textarea hint.
  `self-name` (optional) is the identity name of the document being edited — it is
  removed from the search results so a document can never cite itself (a self-reference
  is a degenerate cycle; see the *Consistency rules* in agora/CLAUDE.md). `inputs?` (default
  true) toggles the **citation feature** (the cite/create search box); pass false for a kind
  that may not have inputs (see `dk/kind-allows-inputs?`) — the prose textarea stays. `publication-id`
  (the active publication) scopes the search so a **draft** predecessor in the same publication is
  citable, and gathers an inline-created predecessor into it."
  [_value _set-text! _placeholder _self-name _inputs? _publication-id]
  (let [node (atom nil)
        setter (atom nil)
        ;; the active publication, refreshed on each render (form-2): search is scoped to it (so a
        ;; draft predecessor in the same publication is citable) and an inline-created predecessor is
        ;; gathered by it
        pub (atom nil)
        q (r/atom "")
        results (r/atom [])
        busy? (r/atom false)
        ;; inline new-KI form (fork 3): richer than a bare title — capture title + statement +
        ;; kind before creating the predecessor. Created as a draft, spliced in as a citation.
        form? (r/atom false)
        nt (r/atom "")
        nx (r/atom "")
        nk (r/atom :inference)
        fit! (fn []
               (when-let [el @node]
                 (set! (.. el -style -height) "auto")
                 (set! (.. el -style -height) (str (.-scrollHeight el) "px"))))
        insert! (fn [ki]
                  (when-let [el @node]
                    ;; the target form carries the language (`[[ki:name:lang@major]]`); fall back
                    ;; to the bare form only if the picked KI somehow has no language
                    (let [tag (str "[["
                                   (or (:type ki) "ki")
                                   ":"
                                   (:name ki)
                                   (when (:lang ki) (str ":" (:lang ki)))
                                   "@"
                                   (:major ki)
                                   "]]")
                          v (or (.-value el) "")
                          s (.-selectionStart el)
                          e (.-selectionEnd el)
                          v' (str (subs v 0 s) tag (subs v e))]
                      (@setter v')
                      (reset! q "")
                      (reset! results [])
                      (js/setTimeout (fn []
                                       (.focus el)
                                       (let [pos (+ s (count tag))] (.setSelectionRange el pos pos))
                                       (fit!))
                                     0))))
        search! (fn [text lang]
                  (reset! q text)
                  (if (str/blank? text)
                    (reset! results [])
                    (-> (js/fetch (str "/agora/api/documents/ki?lang="
                                       lang
                                       "&q="
                                       (js/encodeURIComponent text)
                                       (when @pub
                                         (str "&publication=" (js/encodeURIComponent @pub))))
                                  #js {:headers #js {"Accept" "application/json"}})
                        (.then #(.json %))
                        (.then #(reset! results (js->clj % :keywordize-keys true)))
                        (.catch (fn [_] (reset! results []))))))
        ;; open the inline new-KI form, seeding the title from the current query
        open-form! (fn [] (reset! nt @q) (reset! nx "") (reset! nk :inference) (reset! form? true))
        ;; create the predecessor from the form (statement optional — falls back to the title so
        ;; the required :text is always non-blank), then splice its citation at the cursor
        submit! (fn [lang]
                  (when-not (or @busy? (str/blank? @nt))
                    (reset! busy? true)
                    (-> (js/fetch "/agora/api/documents/ki"
                                  #js {:method "POST"
                                       :headers #js {"Content-Type" "application/json"
                                                     "Accept" "application/json"}
                                       :body (js/JSON.stringify
                                              (clj->js {:title @nt
                                                        :kind @nk
                                                        :lang lang
                                                        :publication-id @pub
                                                        :text (let [t (str/trim @nx)]
                                                                (if (str/blank? t) @nt t))}))})
                        (.then #(.json %))
                        (.then (fn [ki]
                                 (reset! busy? false)
                                 (reset! form? false)
                                 (insert! (js->clj ki :keywordize-keys true))))
                        (.catch (fn [_] (reset! busy? false))))))]
    (fn [value set-text! placeholder self-name inputs? publication-id]
      (reset! setter set-text!)
      (reset! pub publication-id)
      (let [lang @(rf/subscribe [::i18n/lang])
            ;; every citation is written into the prose as a `[[ki:…]]` token
            pick! (fn [k] (insert! k))]
        [:div
         [ui/composed-field {:element :textarea
                             :ref (fn [el] (reset! node el) (when el (js/setTimeout fit! 0)))
                             :placeholder placeholder
                             :value (or value "")
                             :on-text (fn [v] (set-text! v) (fit!))
                             :style {:width "100%"
                                     :box-sizing "border-box"
                                     :resize "none"
                                     :overflow "hidden"
                                     :min-height "8em"
                                     :padding "0.6em"
                                     :font-family "inherit"
                                     :font-size "1.02em"
                                     :line-height "1.55"
                                     :border "1px solid #ccc"
                                     :border-radius "0.3em"}}]
         ;; the KI cite/create search — hidden for inputless kinds
         (when (not (false? inputs?))
           [:div {:style {:position "relative"
                          :margin "0.4em 0 0"}}
            [ui/composed-field {:type "text"
                                :placeholder (i18n/t lang :cite/search-ph)
                                :value @q
                                :on-text #(search! % lang)
                                :style {:width "100%"
                                        :box-sizing "border-box"
                                        :padding "0.45em"
                                        :font-size "0.9em"
                                        :border "1px solid #ccc"
                                        :border-radius "0.3em"}}]
            (when (and (not @form?) (not (str/blank? @q)))
              (into [:div {:style {:position "absolute"
                                   :z-index 20
                                   :left 0
                                   :right 0
                                   :margin-top "0.2em"
                                   :background "#fff"
                                   :border "1px solid #ddd"
                                   :border-radius "0.4em"
                                   :box-shadow "0 4px 12px rgba(0,0,0,0.1)"
                                   :max-height "16em"
                                   :overflow-y "auto"}}]
                    (conj (mapv (fn [k]
                                  ^{:key (:id k)}
                                  [:button {:on-click #(pick! k)
                                            :style result-btn-style}
                                   [mini-kind-badge (:kind k)]
                                   [:span {:style {:font-weight 600}}
                                    (or (:title k) (humanize (:name k)))]])
                                ;; a document can't cite itself — drop its own lineage
                                (remove #(= (:name %) self-name) @results))
                          ^{:key "__new__"}
                          [:button {:on-click #(open-form!)
                                    :disabled @busy?
                                    :style
                                    (assoc result-btn-style :color "#b9770e" :font-weight 700)}
                           (str "＋ " (i18n/t lang :cite/create-new) " “" @q "”")])))
            ;; fork 3: inline new-KI form — capture title + statement + kind, created as a draft
            ;; predecessor and spliced in as a citation (the new input is hidden until published)
            (when @form?
              [:div {:style {:margin-top "0.3em"
                             :padding "0.6em"
                             :border "1px solid #d9c9a8"
                             :background "#fdf9f0"
                             :border-radius "0.4em"
                             :display "flex"
                             :flex-direction "column"
                             :gap "0.45em"}}
               [ui/composed-field {:type "text"
                                   :placeholder (i18n/t lang :cite/new-title-ph)
                                   :value @nt
                                   :auto-focus true
                                   :on-text #(reset! nt %)
                                   :style {:width "100%"
                                           :box-sizing "border-box"
                                           :padding "0.45em"
                                           :font-size "0.95em"
                                           :font-weight 600
                                           :border "1px solid #ccc"
                                           :border-radius "0.3em"}}]
               [ui/composed-field {:element :textarea
                                   :placeholder (i18n/t lang :cite/new-statement-ph)
                                   :value @nx
                                   :on-text #(reset! nx %)
                                   :style {:width "100%"
                                           :box-sizing "border-box"
                                           :min-height "4em"
                                           :resize "vertical"
                                           :padding "0.45em"
                                           :font-family "inherit"
                                           :font-size "0.9em"
                                           :line-height "1.45"
                                           :border "1px solid #ccc"
                                           :border-radius "0.3em"}}]
               [kind-picker @nk #(reset! nk %)]
               [:div {:style {:display "flex"
                              :gap "0.4em"
                              :justify-content "flex-end"}}
                [:button {:on-click #(reset! form? false)
                          :style {:padding "0.4em 0.7em"
                                  :border "1px solid #ccc"
                                  :background "#fff"
                                  :border-radius "0.3em"
                                  :cursor "pointer"
                                  :font-size "0.88em"}}
                 "✕"]
                [:button {:on-click #(submit! lang)
                          :disabled (or @busy? (str/blank? @nt))
                          :style {:padding "0.4em 1em"
                                  :border "none"
                                  :background "#2b8a3e"
                                  :color "#fff"
                                  :border-radius "0.3em"
                                  :cursor "pointer"
                                  :font-weight 600
                                  :font-size "0.88em"}}
                 (i18n/t lang :cite/create-new)]]])])]))))
