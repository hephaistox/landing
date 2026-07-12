(ns landing.agora.document-domain
  "The Agora knowledge-graph domain: the vocabulary and the pure rules for wiring KIs
  together, shared by the backend and the frontend (cljc).

  No I/O and no persistence format. Effectful lookups (`latest-of`, load, persist)
  and EDN (de)serialization are supplied by the `landing.agora.document` adapter on the
  server, the SPA on the client. Persistence (SQL, cache) and transport (HTTP) are
  adapters *around* this core, so the wiring logic lives in exactly one place and one
  technology.

  A KI's identity is its **TNLR** = (type, name, lang, major), where `type` is the
  object type (`ki` / `objection`, the T); its latest minor is the current version.
  Its epistemic register is a separate `kind`. Its inputs are declared as TNLRs and
  each pinned to a concrete predecessor id."
  (:require
   [clojure.string :as str]))

;; --- Identity slug & cid --------------------------------------------------------------
;; A document's `name` is an opaque, stable **cid** — never derived from the title. The
;; human URL carries a decorative `<cid>~<title-slug>` key (cid first, then the readable
;; slug); `~` never occurs in a slug or a cid, so the cid is recovered as everything
;; *before* the first `~`. Shared clj/cljs so the server (SEO/sitemap) and the SPA build
;; byte-identical URLs and resolve them the same way.

(defn slugify
  "A URL slug from a title: `\"L'Être\"` → `\"l-etre\"`. Accents stripped, every run of
  non-alphanumerics becomes one `-`. Blank → \"untitled\"."
  [s]
  (let [stripped #?(:clj (-> (java.text.Normalizer/normalize (or s "")
                                                             java.text.Normalizer$Form/NFD)
                             (str/replace #"\p{M}+" ""))
                    :cljs (-> (.normalize (or s "") "NFD")
                              (str/replace #"[\u0300-\u036f]" "")))
        base (-> stripped
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"(^-+)|(-+$)" ""))]
    (if (str/blank? base) "untitled" base)))

(defn permalink-slug
  "A document's decorative URL key — `<cid>~<title-slug>` (or bare `<cid>` when the title
  yields no slug). Resolution keeps only the cid (see `cid-of`), so the URL tracks the
  current title while references resolve by the immutable cid."
  [cid title]
  (let [s (slugify title)
        ;; cap the decorative part so a long title can't bloat the URL (or exceed the
        ;; API path limit); trim a trailing `-` left by the cut
        s (str/replace (subs s 0 (min 80 (count s))) #"-+$" "")]
    (if (or (str/blank? s) (= s "untitled")) cid (str cid "~" s))))

(defn cid-of
  "The stable cid parsed from a permalink key `<cid>~<slug>` (or a bare `<cid>`) — the part
  before the first `~`."
  [k]
  (let [k (str k)]
    (if-let [i (str/index-of k "~")]
      (subs k 0 i)
      k)))

(def kinds
  "The document `kind`s — canonical domain data for a document's kind, in display order and
  **partitioned by object type**. *KI* kinds sit on an epistemic axis (*how is this known?* —
  inference / prediction / definition / belief / assumption, plus `source`); *article* kinds sit
  on a rhetorical axis (*what is this prose doing?* — `explainer` today, evangelism /
  call-to-action later). Each kind declares its **capabilities and presentation as data**, so
  consumers (backend + UI) read a field rather than branching on a specific kind:
   - `:object-type` — which document type carries it (`\"ki\"` / `\"article\"`); drives the
     per-type kind enum and selector (`kind-ids-of`).
   - `:color`  — accent colour.
   - `:inputs?` — **may a document of this kind take inputs** (in-text `[[ki:…]]` citations)? A
     `source` is a leaf work and takes none; everything else does. This one flag drives both
     the backend (input derivation) and the UI (whether the quotation feature is shown).
   - `:def-name` + `:def-major` — a pointer to the KI that *defines* the kind. The rest of that
     identity is implied — `type` is `ki`, `minor` resolves to the latest, `lang` is the
     reader's — so only name + major are declared here (see `kind-def`).
  The set is NOT enforced by the DB; the API validates against it and the UI renders it."
  [{:id :inference
    :color "#2c5aa0"
    :inputs? true
    :object-type "ki"
    :def-name "type-inference"
    :def-major 1}
   {:id :prediction
    :color "#0b7285"
    :inputs? true
    :object-type "ki"
    :def-name "type-prediction"
    :def-major 1}
   {:id :definition
    :color "#a61e8c"
    :inputs? true
    :object-type "ki"
    :def-name "type-definition"
    :def-major 1}
   ;; The declared-foundation kinds are deliberately just two, a decidable binary: a `belief` is
   ;; a foundation you **hold/commit to** (any register — personal, civic or formal); an
   ;; `assumption` is one you **suppose provisionally**. (An earlier `postulate`/`position`/
   ;; `credo` split was dropped as mechanically identical and hard to choose between.)
   {:id :belief
    :color "#2b8a3e"
    :inputs? true
    :object-type "ki"
    :def-name "type-belief"
    :def-major 1}
   {:id :assumption
    :color "#e8590c"
    :inputs? true
    :object-type "ki"
    :def-name "type-assumption"
    :def-major 1}
   ;; --- annotation kinds: they relate to another claim rather than *deriving* one ---
   ;; `illustration` gives a concrete example of a claim (no reasoning implication — nothing
   ;; follows from it); `counter-example` refutes a *general* claim with one contradicting
   ;; instance ("Einstein is smart" vs "all people are dumb"), forcing it to narrow or withdraw.
   {:id :illustration
    :color "#1098ad"
    :inputs? true
    :object-type "ki"
    :def-name "type-illustration"
    :def-major 1}
   {:id :counter-example
    :color "#e03131"
    :inputs? true
    :object-type "ki"
    :def-name "type-counter-example"
    :def-major 1}
   ;; `source` is the kind of a bibliographic **quotation** KI (one idea/quote from a shared
   ;; book in `AGORA_SOURCE`; see landing.agora.source). **No inputs** (a quotation quotes
   ;; nothing further); `:in-text? false` — when a KI quotes a source, the citation is an
   ;; *input edge only*, never written into the prose (see `kind-quotes-in-text?`); no statement
   ;; scaffold (a source isn't "<author> <verb> that …"); self-hosted by a `type-source`
   ;; definition KI.
   {:id :source
    :color "#495057"
    :inputs? false
    :in-text? false
    :object-type "ki"
    :def-name "type-source"
    :def-major 1}
   ;; --- article kinds (rhetorical axis: what is the prose doing?) ---
   ;; `explainer` is the neutral base — an article that lays out and links the graph's claims
   ;; into readable prose (informing, not advocating). Future article kinds (evangelism,
   ;; call-to-action…) join here. Self-hosted by a `type-explainer` definition KI like the KI
   ;; kinds, so it gets the same badge → definition affordance.
   {:id :explainer
    :color "#6741d9"
    :inputs? true
    :object-type "article"
    :def-name "type-explainer"
    :def-major 1}])

(def kind-ids
  "All kind ids (keywords), in display order, across every object type."
  (mapv :id kinds))

(defn kinds-of
  "The kinds declared for object type `object-type` (\"ki\" / \"article\"), in display order."
  [object-type]
  (filterv #(= object-type (:object-type %)) kinds))

(defn kind-ids-of
  "The kind ids (keywords) for `object-type`, in display order; the **first is that type's
  default** (inference for KIs, explainer for articles)."
  [object-type]
  (mapv :id (kinds-of object-type)))

(def kind-color
  "kind name (string) → accent colour."
  (into {} (map (juxt (comp name :id) :color)) kinds))

(def ^:private kind-inputs?
  "kind name (string) → whether a KI of that kind may take inputs."
  (into {} (map (juxt (comp name :id) :inputs?)) kinds))

(defn kind-allows-inputs?
  "May a document of `kind` have inputs (in-text `[[ki:…]]` citations)? The single source of
  this rule: it reads the kind's `:inputs?` field, so neither the backend (input derivation)
  nor the UI (whether to show the quotation feature) branches on a specific kind. An
  absent/unknown kind (e.g. an article, which has none) defaults to **yes** — only a kind that
  declares `:inputs? false` (a source) is inputless."
  [kind]
  (not (false? (get kind-inputs? kind))))

(def ^:private kind-in-text?
  "kind name (string) → whether *quoting* a KI of that kind writes the citation into the prose."
  (into {} (map (juxt (comp name :id) :in-text?)) kinds))

(defn kind-quotes-in-text?
  "When a KI quotes a document of `kind`, is the citation written **into the prose** (an inline
  `[[ki:…]]` token, the default) or recorded as an **input edge only**? Reads the quoted kind's
  `:in-text?` field, so the citation UI + input derivation don't branch on a specific kind. An
  absent/unknown kind defaults to **in-text** — only `:in-text? false` (a source) is edge-only."
  [kind]
  (not (false? (get kind-in-text? kind))))

(def kind-def
  "kind name (string) → the identity of the KI that defines it: `{:type :name :major}`.
  `type` is always `ki` and `minor` is omitted (it resolves to the latest via by-major);
  the caller supplies the reader's `:lang`. This is the single source of the kind ↔
  definition-KI link — the badge (frontend) and the type seed (backend) both read it, so
  the `type-<kind>` slug convention lives in exactly one place."
  (into {}
        (map (juxt (comp name :id)
                   (fn [{:keys [def-name def-major]}]
                     {:type "ki"
                      :name def-name
                      :major def-major})))
        kinds))

;; --- Kind-guided statement scaffold ---------------------------------------------------
;; Each epistemic kind (except the open `inference`) scaffolds the opening of the statement,
;; so the kind is enforced by the grammar rather than being a decorative badge. Two shapes:
;;   - author-attributed (belief/assumption/prediction): "<author> <verb> that "
;;   - term contract (definition): "<term> means "
;; `inference` has no scaffold (free-form). Only the author's **body** is stored in `:text`;
;; the prefix is DERIVED here (shared clj + cljs) so it stays correct as kind/author change
;; and the SPA can render it instantly, without waiting on the read endpoint.

(def statement-say
  "kind (string) → {:subject :author|:term, :phrase {lang → connector}} — the scaffold for
  that kind's statement opening. Absent for the open `inference` (free-form)."
  {"belief" {:subject :author
             :phrase {"en" "believes that"
                      "fr" "croit que"}}
   "assumption" {:subject :author
                 :phrase {"en" "assumes that"
                          "fr" "suppose que"}}
   "prediction" {:subject :author
                 :phrase {"en" "predicts that"
                          "fr" "prédit que"}}
   "definition" {:subject :term
                 :phrase {"en" "designates"
                          "fr" "désigne"}}})

(defn statement-subject-kind
  "Whether `kind`'s prefix subject is the `:author` or the `:term`, or nil for a free-form
  kind (`inference`)."
  [kind]
  (:subject (get statement-say kind)))

(defn- cap-first
  [s]
  (let [s (str s)] (if (str/blank? s) s (str (str/upper-case (subs s 0 1)) (subs s 1)))))

(defn statement-prefix
  "The kind-guided opening (with a trailing space) for a statement in `lang`, or nil for a
  free-form kind. `subject` is the attributed author (author-kinds) or the defined term
  (definition). E.g. (\"belief\" \"en\" \"Sun Tzŭ\") → \"Sun Tzŭ believes that \"."
  [kind lang subject]
  (when-let [{:keys [phrase]} (get statement-say kind)]
    (when-let [connector (or (get phrase lang) (get phrase "en"))]
      (when-not (str/blank? subject) (str (cap-first subject) " " connector " ")))))

(defn attributed-author
  "The person a statement is attributed to, in priority order:
   1. `:source`'s author — for a `kind=source` KI itself (its `:source` resolves to the work);
   2. `:quote-author-name` — for a KI that **quotes a source** (the work-author of the source-KI
      it inputs), computed by the read layer from the inputs;
   3. else the document's own author.
  So \"Led Zeppelin est le meilleur groupe de rock\" (a position quoting a Rolling Stone source
  by David Fricke) reads \"David Fricke soutient que …\"."
  [doc]
  (or (:author-name (:source doc)) (:quote-author-name doc) (:author doc)))

(defn statement-prefix-of
  "The kind-guided opening for `doc` in `lang` (its subject resolved from the doc), or nil
  for a free-form kind. Rendered *separately* from the body so the citation-parsed prose can
  follow a plain-text prefix (read page, editor label)."
  [doc lang]
  (let [kind (:kind doc)
        subject (case (statement-subject-kind kind)
                  :author (attributed-author doc)
                  :term (:title doc)
                  nil)]
    (statement-prefix kind lang subject)))

(defn compose-statement
  "The full statement sentence for `doc` in `lang`: the kind-guided prefix + the authored
  `body`. Author-kinds prepend `<attributed-author> <verb> that `; `definition` prepends
  `<title> designates `; free-form kinds (`inference`) return the body unchanged."
  [doc lang body]
  (str (statement-prefix-of doc lang) body))

(def object-types
  "The identity T values (object types sharing the single AGORA_DOCUMENT table)."
  [:ki :objection :article])

;; --- article KI-citation grammar ---------------------------------------------
;; A `[[ki:<name>@<major>]]` (or `…|custom text]]`) token in an article body cites a
;; KI. The grammar is defined once here so the article renderer (frontend) and the
;; citation extractor (backend) never drift.

(def cite-pattern
  "Regex for one in-body KI citation: capture groups are name, major, optional text."
  #"\[\[ki:([^@\]|]+)@(\d+)(?:\|([^\]]+))?\]\]")

(defn- parse-major
  [s]
  #?(:clj (Integer/parseInt s)
     :cljs (js/parseInt s 10)))

(defn cite-refs
  "The distinct KIs cited in `body`, as input declarations for a node in `lang`:
  a vector of TNLRs {:type \"ki\" :name … :lang lang :major …}, order-preserving and
  deduped by (name, major). These are exactly KI inputs, so an article reuses the
  whole input/pin/successor model."
  [body lang]
  (->> (re-seq cite-pattern (or body ""))
       (map (fn [[_ nm mj]]
              {:type "ki"
               :name nm
               :lang lang
               :major (parse-major mj)}))
       (distinct)
       (vec)))

;; --- prose block structure (shared so the SPA + SSR renderers never drift) ---------
(def ^:private bullet-pattern
  "A line that is a bullet item: a `-` or `*` marker followed by whitespace (optionally
  indented). The trailing whitespace requirement means a bare `-` is prose, not a bullet."
  #"^[ \t]*[-*][ \t]+")

(defn parse-blocks
  "Structure prose `text` into renderable blocks so the SPA and the server-rendered body
  agree:
   - `{:type :ul :items [line …]}` — a run of bullet lines (`- ` / `* `), markers stripped;
   - `{:type :p  :lines [line …]}` — a run of other non-blank lines. A single line-break is a
     new line within the paragraph (render as `<br>`); a **blank line separates blocks**.
  Inline `[[ki:…]]` citations are left intact for the caller to resolve."
  [text]
  (let [class (fn [l]
                (cond
                  (str/blank? l) :blank
                  (re-find bullet-pattern l) :ul
                  :else :p))]
    (->> (str/split (or text "") #"\n")
         (partition-by class)
         (keep (fn [grp]
                 (case (class (first grp))
                   :blank nil
                   :ul {:type :ul
                        :items (mapv #(str/replace % bullet-pattern "") grp)}
                   :p {:type :p
                       :lines (vec grp)})))
         vec)))

(defn strip-cite
  "Remove every `[[ki:<name>@<major>…]]` citation of (`name`, `major`) from `text`,
  leaving its display text — the custom label if the token had one, else the bare name —
  as plain prose. Used when an input is dropped from the input field: the inline mention
  stays readable, but the citation (and therefore the input edge, which is derived from
  the text) is gone and can't be re-derived on the next edit."
  [text name major]
  (if (str/blank? text)
    text
    (str/replace text
                 cite-pattern
                 (fn [[whole nm mj label]]
                   (if (and (= nm name) (= (parse-major mj) major)) (or label nm) whole)))))

;; --- TNLR --------------------------------------------------------------------

(defn tnlr
  "The TNLR of a node or ref map — {:type :name :lang :major} (type = object type)."
  [m]
  (select-keys m [:type :name :lang :major]))

(defn tnlr-key
  "A comparable/cacheable vector form of a TNLR: [type name lang major]."
  [m]
  [(:type m) (:name m) (:lang m) (:major m)])

(defn same-tnlr? [a b] (= (tnlr-key a) (tnlr-key b)))

;; --- inputs ------------------------------------------------------------------
;; An input has two halves that live in different places:
;;   - the DECLARATION — a TNLR — is authored, part of the KI's meaning, and lives in
;;     the immutable `content` (`:inputs [TNLR…]`). Changing it versions the KI.
;;   - the PIN — the resolved predecessor id — is derived (re-resolved when a
;;     predecessor gets a new minor) and lives in the mutable `computed`
;;     (`:pins {tnlr-key → id}`).

(def max-inputs
  "Cap on the number of declared inputs a single KI may carry. Inputs are added one
  at a time (each a new minor), with no natural bound, so this protects the read
  model — the envelope blob size and the successor-cache fan-out — from an
  unbounded input list. A generous ceiling; real reasoning steps use a handful."
  50)

(defn add-declared
  "Add TNLR `t` to the declared inputs (dedup by TNLR). Returns the new declarations."
  [tnlrs t]
  (conj (filterv #(not (same-tnlr? % t)) tnlrs) (tnlr t)))

(defn drop-declared
  "Remove the declared input on TNLR `t`."
  [tnlrs t]
  (filterv #(not (same-tnlr? % t)) tnlrs))

(defn pin-all
  "Resolve every declared TNLR to its current latest id → {tnlr-key → id}. `latest-of`
  maps a TNLR → id."
  [tnlrs latest-of]
  (into {} (map (fn [t] [(tnlr-key t) (latest-of t)])) tnlrs))

(defn repin
  "Point the pin for TNLR `t` at `new-id` (used when `t` gets a new minor)."
  [pins t new-id]
  (assoc pins (tnlr-key t) new-id))

(defn input-refs
  "The API-facing input refs — each declared TNLR plus its pinned id."
  [tnlrs pins]
  (mapv (fn [t] (assoc t :id (get pins (tnlr-key t)))) tnlrs))

;; --- successor index ---------------------------------------------------------

(defn successor-tuples
  "The reverse-edge rows for `successor-id`: one {:tnlr … :successor-id …} per declared
  input TNLR."
  [successor-id tnlrs]
  (mapv (fn [t]
          {:tnlr t
           :successor-id successor-id})
        tnlrs))
