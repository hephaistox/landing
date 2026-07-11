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
  (let [stripped #?(:clj (-> (java.text.Normalizer/normalize (or s "") java.text.Normalizer$Form/NFD)
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
    (if-let [i (str/index-of k "~")] (subs k 0 i) k)))

(def kinds
  "The epistemic `kind`s — canonical domain data for a KI's kind, in display order.
  Each carries its accent colour, its conceptual `family` (`derived` — has inputs;
  `verifiable` — settles at a date; `foundation` — a declared starting point), and a
  pointer to the KI that *defines* it: `:def-name` + `:def-major` (the definition KI's
  identity name and major). The rest of that identity is implied — its `type` is `ki`,
  its `minor` resolves automatically to the latest, and its `lang` is the reader's
  interface language — so only name + major need to be declared here (see `kind-def`).
  The set is NOT enforced by the DB; the API validates against it and the UI renders it."
  [{:id :inference
    :color "#2c5aa0"
    :family :derived
    :def-name "type-inference"
    :def-major 1}
   {:id :prediction
    :color "#0b7285"
    :family :verifiable
    :def-name "type-prediction"
    :def-major 1}
   {:id :postulate
    :color "#6741d9"
    :family :foundation
    :def-name "type-postulate"
    :def-major 1}
   {:id :definition
    :color "#a61e8c"
    :family :foundation
    :def-name "type-definition"
    :def-major 1}
   {:id :position
    :color "#b9770e"
    :family :foundation
    :def-name "type-position"
    :def-major 1}
   {:id :belief
    :color "#2b8a3e"
    :family :foundation
    :def-name "type-belief"
    :def-major 1}
   {:id :credo
    :color "#c92a2a"
    :family :foundation
    :def-name "type-credo"
    :def-major 1}])

(def kind-ids "The kind ids (keywords), in display order." (mapv :id kinds))

(def kind-color
  "kind name (string) → accent colour."
  (into {} (map (juxt (comp name :id) :color)) kinds))

(def kind-family
  "kind name (string) → conceptual family (keyword)."
  (into {} (map (juxt (comp name :id) :family)) kinds))

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
;;   - author-attributed (belief/credo/position/postulate/prediction): "<author> <verb> that "
;;   - term contract (definition): "<term> means "
;; `inference` has no scaffold (free-form). Only the author's **body** is stored in `:text`;
;; the prefix is DERIVED here (shared clj + cljs) so it stays correct as kind/author change
;; and the SPA can render it instantly, without waiting on the read endpoint.

(def statement-say
  "kind (string) → {:subject :author|:term, :phrase {lang → connector}} — the scaffold for
  that kind's statement opening. Absent for the open `inference` (free-form)."
  {"belief"     {:subject :author :phrase {"en" "believes that" "fr" "croit que"}}
   "credo"      {:subject :author :phrase {"en" "affirms that"  "fr" "professe que"}}
   "position"   {:subject :author :phrase {"en" "holds that"    "fr" "soutient que"}}
   "postulate"  {:subject :author :phrase {"en" "posits that"   "fr" "postule que"}}
   "prediction" {:subject :author :phrase {"en" "predicts that" "fr" "prédit que"}}
   "definition" {:subject :term   :phrase {"en" "means"         "fr" "signifie"}}})

(defn statement-subject-kind
  "Whether `kind`'s prefix subject is the `:author` or the `:term`, or nil for a free-form
  kind (`inference`)."
  [kind]
  (:subject (get statement-say kind)))

(defn- cap-first
  [s]
  (let [s (str s)]
    (if (str/blank? s) s (str (str/upper-case (subs s 0 1)) (subs s 1)))))

(defn statement-prefix
  "The kind-guided opening (with a trailing space) for a statement in `lang`, or nil for a
  free-form kind. `subject` is the attributed author (author-kinds) or the defined term
  (definition). E.g. (\"belief\" \"en\" \"Sun Tzŭ\") → \"Sun Tzŭ believes that \"."
  [kind lang subject]
  (when-let [{:keys [phrase]} (get statement-say kind)]
    (when-let [connector (or (get phrase lang) (get phrase "en"))]
      (when-not (str/blank? subject)
        (str (cap-first subject) " " connector " ")))))

(defn attributed-author
  "The person a statement is attributed to: the cited source's author if the document cites
  a source, else the document's own author. One source per document, so unambiguous."
  [doc]
  (or (:author-name (:source doc)) (:author doc)))

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
  `<title> means `; free-form kinds (`inference`) return the body unchanged."
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
