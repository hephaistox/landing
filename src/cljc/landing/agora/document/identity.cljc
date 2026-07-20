(ns landing.agora.document.identity
  "New Wire.
  Document **identity** and the edges between documents
  how a document is named, addressed, linked and resolved.

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
  - **TNR** listing the minor version and language of a TNLR

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
  "The identity document values (object types sharing the single AGORA_DOCUMENT table)."
  [:ki :objection :article])

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
  "Parse the `body` and returns all citation - as in [[cite-pattern]], returns a vector of TNLRs
  {:type … :name … :lang … :major …} order-preserving and deduped."
  [body lang]
  (->> (re-seq cite-pattern (or body ""))
       (keep (fn [[_ type nm lang-tok mj]]
               (let [type (keyword type)
                     lang-tok (keyword lang-tok)]
                 (when (object-types-set type)
                   {:type type
                    :name nm
                    :lang (or lang-tok lang)
                    :major (parse-major mj)}))))
       (distinct)
       (vec)))

(defn strip-cite
  "Remove every citation from `body`, leaving its display body —
  the custom label if the token had one, else the bare name — as plain prose."
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

(defn pinned?
  "Is input `inp` **pinned** — frozen to an exact version by an inline `:id` — rather than
  **floating** (a bare TNLR that follows its lineage's latest minor)?"
  [inp]
  (some? (:id inp)))

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
  "Resolve every **floating** input to its lineage's current id; a **pinned** input keeps its own
  frozen `:id`. → {tnlr-key → id}. `latest-of` (a TNLR → id) resolves the floating ones only — a
  pinned input needs no resolution (it already names an exact version), which is exactly what
  isolates a change: a new minor never repins it."
  [inputs latest-of]
  (into {} (map (fn [inp] [(tnlr-key inp) (or (:id inp) (latest-of inp))])) inputs))

(defn repin
  "Point the pin for TNLR `doc-ref` at `new-id` (used when `doc-ref` gets a new minor). The caller repins only
  **floating** successor inputs — a **pinned** input (`pinned?`) is frozen and must be skipped,
  which is how a change stays isolated."
  [pins doc-ref new-id]
  (assoc pins (tnlr-key doc-ref) new-id))

(defn input-refs
  "The API-facing input refs — each input plus its resolved id: a **pinned** input's own
  frozen `:id`, else the **floating** pin from `pins`. Preferring the inline id lets a pinned ref
  survive even a stale pin cache."
  [inputs pins]
  (mapv (fn [inp] (assoc inp :id (or (:id inp) (get pins (tnlr-key inp))))) inputs))

;; --- outputs (successor index) -----------------------------------------------

(defn successor-tuples
  "The reverse-edge rows for `successor-id`: one {:tnlr … :successor-id …} per declared
  input TNLR."
  [successor-id tnlrs]
  (mapv (fn [doc-ref]
          {:tnlr doc-ref
           :successor-id successor-id})
        tnlrs))
