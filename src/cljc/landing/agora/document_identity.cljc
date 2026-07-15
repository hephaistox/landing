(ns landing.agora.document-identity
  "Document **identity** and the edges between documents — the pure, shared (cljc) core of how a
  document is named, addressed, linked and resolved. No I/O and no persistence format.

  Identity is a **TNLR** = (Type, Name, Lang, major Release); the latest minor is the current
  version. A document's `name` is an opaque, stable **cid** (never derived from the title); the
  human URL carries a decorative `<cid>~<title-slug>` key, resolved back to the cid. Edges are
  TNLRs too: **inputs** (a document's declared predecessor TNLRs — expressed in prose as
  `[[ki:…]]` citations — each pinned to a concrete predecessor id) and **outputs** (the
  reverse-edge rows a document contributes as a successor)."
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

;; --- object types ---------------------------------------------------------------------

(def object-types
  "The identity T values (object types sharing the single AGORA_DOCUMENT table)."
  [:ki :objection :article])

;; --- TNLR -----------------------------------------------------------------------------

(defn tnlr
  "The TNLR of a node or ref map — {:type :name :lang :major} (type = object type)."
  [m]
  (select-keys m [:type :name :lang :major]))

(defn tnlr-key
  "A comparable/cacheable vector form of a TNLR: [type name lang major]."
  [m]
  [(:type m) (:name m) (:lang m) (:major m)])

(defn same-tnlr? [a b] (= (tnlr-key a) (tnlr-key b)))

;; --- citation grammar (the input link, expressed in prose) ----------------------------
;; A `[[ki:<name>@<major>]]` (or `…|custom text]]`) token in a body cites a KI. The grammar
;; is defined once here so the renderer (frontend) and the citation extractor (backend) never
;; drift. A citation IS an input edge — its declaration is a TNLR.

(def cite-pattern
  "Regex for one in-body KI citation `[[ki:<name>(:<lang>)?@<major>(|<label>)?]]`. Capture groups:
  (1) name, (2) **optional** language, (3) major, (4) optional label. The language is optional as a
  *transition* fallback — an old `[[ki:name@major]]` inherits the citing document's language — but
  the **target form carries it** (`[[ki:name:en@major]]`), so a citation names a full TNLR and can
  point at a specific-language sibling. Defined once so the renderer (frontend) and the citation
  extractor (backend) never drift."
  #"\[\[ki:([^@:\]|]+)(?::([^@:\]|]+))?@(\d+)(?:\|([^\]]+))?\]\]")

(defn- parse-major
  [s]
  #?(:clj (Integer/parseInt s)
     :cljs (js/parseInt s 10)))

(defn cite-refs
  "The distinct KIs cited in `body`, as input declarations — a vector of TNLRs
  {:type \"ki\" :name … :lang … :major …}, order-preserving and deduped. Each citation's language
  is **read from its token** when present (`[[ki:name:en@major]]`), else it falls back to `lang`
  (the citing document's language). These are exactly KI inputs, so an article reuses the whole
  input/pin/successor model."
  [body lang]
  (->> (re-seq cite-pattern (or body ""))
       (map (fn [[_ nm lang-tok mj]]
              {:type "ki"
               :name nm
               :lang (or lang-tok lang)
               :major (parse-major mj)}))
       (distinct)
       (vec)))

(defn strip-cite
  "Remove every `[[ki:<name>…@<major>…]]` citation of (`name`, `major`) from `text`, leaving its
  display text — the custom label if the token had one, else the bare name — as plain prose. Used
  when an input is dropped from the input field: the inline mention stays readable, but the
  citation (and therefore the input edge, which is derived from the text) is gone and can't be
  re-derived on the next edit."
  [text name major]
  (if (str/blank? text)
    text
    (str/replace text
                 cite-pattern
                 (fn [[whole nm _lang mj label]]
                   (if (and (= nm name) (= (parse-major mj) major)) (or label nm) whole)))))

;; --- inputs -------------------------------------------------------------------
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

;; --- outputs (successor index) -----------------------------------------------

(defn successor-tuples
  "The reverse-edge rows for `successor-id`: one {:tnlr … :successor-id …} per declared
  input TNLR."
  [successor-id tnlrs]
  (mapv (fn [t]
          {:tnlr t
           :successor-id successor-id})
        tnlrs))
