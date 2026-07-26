(ns landing.agora.document.identity
  "Document **identity** and the edges between documents

  No I/O and no persistence format.

  Identity is a **TNLR** = (Type, Name, Lang, major Release); the latest minor is the current
  version:
  - A `type` is a keyword (e.g. `:ki`, `:article`, ...)
  - A `name` is an opaque, stable **cid** (never derived from the title); the
  human URL carries a decorative `<cid>~<title-slug>` key, resolved back to the cid.
  - `lang` is the language of the document
  - `major` is a major release 
  
  For one **TNLR** many versions are possible:
  - A `minor` release carries that differences

  The whole document has an `id` - a uuid - that identifies it precisely

  Referencing a document is possible through two mechanisms:
  - **pin** a version so the reference is a precise `id`
  - **TNLR** listing the minor version of a document in the same TLNR
  - **TNR** listing all languages and minor version of a TNLR

  That references can be set through a cite,  `[[<type>:<name>(:<lang>)?@<major>(|<label>)?]]`:
  - `lang` is optional so user language will be picked
  - `label` is optional so it replaces the link in the link with that label"
  (:require
   [clojure.string :as str]))

;; --- Identity slug & cid --------------------------------------------------------------

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
  "The stable cid parsed from a `permalink-slug`: `<cid>~<slug>` (or a bare `<cid>`) — the part
  before the first `~`."
  [permalink-slug]
  (let [permalink-slug (str permalink-slug)]
    (if-let [i (str/index-of permalink-slug "~")]
      (subs permalink-slug 0 i)
      permalink-slug)))

;; --- object types ---------------------------------------------------------------------

(def object-types
  "The identity document values (object types sharing the single AGORA_DOCUMENT table). `:source` is
  the bibliographic *work* a `kind=source` citation references — a leaf, never in a public feed."
  [:ki :objection :article :source])

(def object-types-set
  "The identity document values (object types sharing the single AGORA_DOCUMENT table)."
  (into #{} object-types))

;; --- TNLR -----------------------------------------------------------------------------

(defn tnlr
  "The TNLR of a document or ref map — {:type :name :lang :major} (type = object type)."
  [TNLR-or-doc]
  (select-keys TNLR-or-doc [:type :name :lang :major]))

(defn tnlr-key
  "A comparable/cacheable vector form of a TNLR: [type name lang major]."
  [doc-ref]
  [(:type doc-ref) (:name doc-ref) (:lang doc-ref) (:major doc-ref)])

(defn same-tnlr? [a b] (= (tnlr-key a) (tnlr-key b)))

;; --- citation grammar (the input link, expressed in prose) ----------------------------

(def cite-pattern
  "Regex for one in-prose document citation `[[<type>:<name>(:<lang>)?@<major>(|<label>)?]]`.
  Capture groups: (1) target type, (2) name, (3) **optional** language, (4) major, (5) optional
  label."
  #"\[\[([^@:\]|]+):([^@:\]|]+)(?::([^@:\]|]+))?@(\d+)(?:\|([^\]]+))?\]\]")

(defn- parse-major
  [s]
  #?(:clj (Integer/parseInt s)
     :cljs (js/parseInt s 10)))

(defn cite-refs
  "Parse `body` and return the TNLRs of its `[[…]]` citations (see `cite-pattern`) — order-preserving
  and deduped. `:lang` is the citation token's **own** language, or **nil** when the token omits one:
  a bare `[[ki:name@major]]` is language-neutral in the prose, and resolving nil → a context language
  is the *consumer's* job, done where the ref is consumed (`lineage/inputs-of` fills the doc's lang)."
  [body]
  (->> (re-seq cite-pattern (or body ""))
       (keep (fn [[_ type nm lang-tok mj]]
               (let [type (keyword type)]
                 (when (object-types-set type)
                   {:type type
                    :name nm
                    :lang (keyword lang-tok)
                    :major (parse-major mj)}))))
       (distinct)
       (vec)))

(defn strip-cite
  "Unweave the citations of **one** input — the given `TNLR` (type, name, lang, major) — from
  `body`: each matching `[[…]]` token becomes its display text (the custom `|label`, else the bare
  name) as plain prose, while **every other citation is left intact**."
  [body TNLR]
  (if (str/blank? body)
    body
    (let [{:keys [type name lang major]} TNLR]
      (str/replace body
                   cite-pattern
                   (fn [[whole re-type re-name re-lang re-major label]]
                     (if (and (= (keyword re-type) type)
                              (= re-name name)
                              (= (keyword re-lang) lang)
                              (= (parse-major re-major) major))
                       (or label re-name)
                       whole))))))

;; --- inputs -------------------------------------------------------------------

(def max-inputs "Cap on the number of inputs a single KI may carry." 50)

(defn add-input
  "Add `doc-ref` to the `inputs` (dedup by lineage/TNLR). Keeps `doc-ref`'s pin (`:id`) when it
  carries one (a pinned ref), else records the bare TNLR (a floating ref)."
  [inputs doc-ref]
  (conj (filterv #(not (same-tnlr? % doc-ref)) inputs)
        (select-keys doc-ref [:type :name :lang :major :id])))

(defn drop-input
  "Remove the input on TNLR `doc-ref` — matched by lineage, pinned or floating alike."
  [inputs doc-ref]
  (filterv #(not (same-tnlr? % doc-ref)) inputs))

(defn pin-all
  "Resolve every input to a doc carrying its `:id`: a **floating** input gets its lineage's current
  id (via `latest-of`), a **pinned** input keeps its own frozen `:id` — never re-resolved, which is
  what isolates a change (a new minor never repins it). Returns the inputs, each with `:id` set.
  This is the value stored as `computed.:pins`, and — since each entry is already the input plus its
  resolved id — it *is* the API-facing input refs on read (no separate zip step)."
  [inputs latest-of]
  (mapv (fn [inp] (assoc inp :id (or (:id inp) (latest-of inp)))) inputs))

(defn repin
  "Re-point the pin of the input on TNLR `doc-ref` to `new-id` (used when `doc-ref` gets a new
  minor): set that input's `:id` in `pins`, leaving the others untouched. The caller repins only
  **floating** successor inputs — a **pinned** input (`pinned?`) is frozen and skipped, which is how
  a change stays isolated."
  [pins doc-ref new-id]
  (mapv (fn [inp] (if (same-tnlr? inp doc-ref) (assoc inp :id new-id) inp)) pins))

;; --- outputs (successor index) -----------------------------------------------

(defn successor-tuples
  "The reverse-edge rows for `successor-id`: one {:tnlr … :successor-id …} per declared
  input TNLR."
  [successor-id tnlrs]
  (mapv (fn [doc-ref]
          {:tnlr doc-ref
           :successor-id successor-id})
        tnlrs))
