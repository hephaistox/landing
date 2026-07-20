(ns landing.agora.document.lineage
  "New Wire.  The **lineage** layer scoped to *a lineage's set of minors* (resolve which minor is current,
  compute the next, construct the next on create / edit) plus the referential-integrity rules.
  It sits above `document.identity`(TNLR + citation grammar) and `document.kind` (which kinds may take
  inputs), and it is the single home of the rule **a version's text determines its inputs**.

  Storage is an adapter *below* this: a holder (the DB via a `WHERE`, or an in-memory corpus via a
  scan) narrows to a lineage's minors — a plain seq of version maps — and this namespace resolves +
  constructs over them. Whole-corpus graph queries (resolve a TNLR, a document's inputs, a
  publication's members) are the *holder's* composition of these rules, not part of this layer. No
  I/O here — the caller supplies any I/O-sourced fields (author name, timestamp, a fresh id) and
  persists the result."
  (:require
   [landing.agora.document.identity :as di]
   [landing.agora.document.kind     :as dk]))

;; --- content & the input-derivation rule -------------------------------------

(def ^:private not-carried
  "The keys a new minor does **not** carry from the current version — so a storage adapter carries
  an edit forward by `(apply dissoc current not-carried)` and **everything else is kept**. A
  drop-list, not a keep-list: a new *content* key (`:kind :title :text :author :owner-id :inputs
  :source :status`, and any future one) carries automatically, with nothing to remember.
  Dropped are per-version identity (`:type :name :lang :major :minor :id` — reassigned), the
  version's state (`:draft :publication-id`), the derived `:pins`, and `:published-at` (re-stamped
  each version)."
  [:type :name :lang :major :minor :id :draft :publication-id :pins :published-at])

(defn content-for-new-version "Remove version specific data" [doc] (apply dissoc doc not-carried))

(defn inputs-of
  "The input TNLRs of a version, **derived from its content**: the `[[ki:…]]` citations in its
  text (`di/cite-refs`) plus any explicit source `:cites` (edge-only inputs never written into
  prose), all in `lang`. Empty when the kind takes no inputs (`dk/kind-allows-inputs?`). This is
  the one rule 'new text → new inputs', so every caller derives inputs the same way."
  [content lang]
  (if (dk/kind-allows-inputs? (:kind content))
    (->> (concat (di/cite-refs (:text content) lang)
                 (map (fn [{:keys [name major]}]
                        {:type :ki
                         :name name
                         :lang lang
                         :major major})
                      (:cites content)))
         (distinct)
         (vec))
    []))

;; --- resolution over a lineage's minors --------------------------------------
;; `minors` is a seq of versions that all share one TNLR (the caller has already narrowed to a
;; single lineage — the DB with a WHERE clause, the corpus by scanning its vector).

(defn- latest [minors] (when (seq minors) (apply max-key :minor minors)))

(defn latest-published
  "The highest **published** (non-draft) minor among `minors` — the public view. nil for a
  draft-only lineage."
  [minors]
  (let [published (remove :draft minors)] (when (seq published) (apply max-key :minor published))))

(defn resolve-latest
  "The version to serve for `lang` from a TNR's `versions` — every language and minor of one
  (type, name, major). The latest published minor in `lang`, else — cross-language fallback — the
  latest published in `fallback-lang`. nil if neither has a published version. `versions` mixes
  languages; each is resolved by `latest-published`."
  [versions lang fallback-lang]
  (or (latest-published (filter #(= lang (:lang %)) versions))
      (when (not= lang fallback-lang)
        (latest-published (filter #(= fallback-lang (:lang %)) versions)))))

(defn next-minor
  "The minor a new version would take: one past the highest in `minors`, or 0 if empty."
  [minors]
  (if (seq minors) (inc (:minor (latest minors))) 0))

;; --- lifecycle constructors (pure) -------------------------------------------
;; Each returns a new version *value*. The text drives the inputs; the caller adds any
;; I/O-sourced fields (author, timestamp, id) and persists.

(defn create
  "A brand-new version (major 1, minor 0, draft) from `content` — its identity (`:type :name
  :lang`) and authored fields (`:kind :title :text …`) — with `:inputs` derived from its text."
  [content]
  (-> content
      (merge {:major 1
              :minor 0
              :draft true})
      (assoc :inputs (inputs-of content (:lang content)))))

(defn edit
  "A new **minor** of the lineage whose versions are `minors`: carry the current version forward,
  apply `changes`, **re-derive `:inputs` from the (possibly edited) text**, and mark it draft. nil
  if the lineage is empty. Per-version fields (`:id`, `:pins`) are dropped — the caller reassigns
  them on persist."
  [minors changes]
  (when-let [current (latest minors)]
    (let [merged (-> (merge current changes)
                     (dissoc :id :pins))]
      (assoc merged
             :minor (next-minor minors)
             :draft true
             :inputs (inputs-of merged (:lang merged))))))

;; --- a version's declared inputs ---------------------------------------------

(defn declared-inputs
  "A version's declared inputs — its `:inputs` (derived from the text at create/edit time and
  stored on the version). Empty for a leaf or a publication. Resolving each against a holder (the
  corpus, the DB) is the caller's concern."
  [doc]
  (vec (:inputs doc)))

;; --- referential-integrity rules ---------------------------------------------
;; The consistency scan as pure rules. The caller supplies the set of live lineages — the engine
;; from SQL, the corpus from a scan of its vector.

(defn ref-issues
  "The reference-consistency issues of `doc` given `existing` — a set of live lineage keys, each
  `[type name major]`. Returns `{:broken [ref…] :self [ref…]}`:
   - `:broken` — **dangling** refs (a reference whose lineage is not in `existing`, any language);
   - `:self` — **self-references** (a ref to the doc's own lineage — a degenerate cycle).
  Refs are the union of the doc's declared `:inputs` and the `[[ki:…]]` citations re-derived from
  its text (robust to any drift between the two). Each issue is `{:name :major :lang}`. Pure."
  [doc existing]
  (let [self [(:type doc) (:name doc) (:major doc)]
        ref-id (juxt :type :name :major)
        refs (distinct (concat (declared-inputs doc) (di/cite-refs (:text doc) (:lang doc))))
        pick (fn [rs]
               (->> rs
                    (map #(select-keys % [:name :major :lang]))
                    (distinct)
                    (vec)))]
    {:broken (pick (remove #(existing (ref-id %)) refs))
     :self (pick (filter #(= (ref-id %) self) refs))}))

(defn consistency-issues
  "The reference-consistency issues across `docs` — a collection of document *versions*, each an
  identity + content map (`:id :type :name :lang :major :minor :title :inputs :text`) — given
  `existing`, the set of live lineage keys `[type name major]`. One entry per version that has a
  broken or self reference (`ref-issues`), shaped for display:
  `{:id :type :name :lang :major :minor :title :broken […] :self […]}` (`:id` pins the exact
  version for a deep link). Pure — the caller supplies the versions and the live-lineage set: the
  SQL engine reads them in two queries, a corpus scans its vector."
  [docs existing]
  (->> docs
       (keep (fn [doc]
               (let [{:keys [broken self]} (ref-issues doc existing)]
                 (when (or (seq broken) (seq self))
                   (assoc (select-keys doc [:id :type :name :lang :major :minor :title])
                          :broken broken
                          :self self)))))
       vec))
