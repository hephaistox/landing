(ns landing.agora.document
  "The single engine for versioned AGORA_DOCUMENT documents. Every document type is a
  `type` row here, sharing one implementation: create / edit / fetch, explicit inputs
  (add / drop), translate, search, discovery, admin and sitemap are all generic — the
  engine has no per-type behaviour of its own, so it never branches on a specific type.

  All graph decisions live in the domain; this ns only does the generic I/O on top of the
  shared storage primitives (landing.agora.document-store)."
  (:require
   [clojure.string                :as str]
   [landing.agora.db              :as db]
   [landing.agora.document-domain :as domain]
   [landing.agora.document-store  :as store]
   [landing.agora.source          :as source]
   [landing.language              :as language]))

;; Every document's prose lives under one content key, `:text` (older rows used per-type
;; `:statement`/`:body`, unified here). A document's inputs ARE the `[[ki:…]]` citations in
;; that text (parsed on write) — an in-text citation is an input edge.
(defn text-of
  "A document's prose, from the unified `:text` key — falling back to the legacy
  per-type keys (`:statement`/`:body`) for rows written before the fields were unified.
  Those rows migrate to `:text` on their next write (see `normalize-text`)."
  [content]
  (or (:text content) (:statement content) (:body content)))

(defn- normalize-text
  "Fold any prose (`:text`/legacy `:statement`/`:body`) into the single `:text` key and
  drop the legacy ones, so every written version stores prose under exactly one key."
  [content]
  (if-let [t (text-of content)]
    (-> content
        (assoc :text t)
        (dissoc :statement :body))
    content))

(defn- normalize-source
  "Store `content.:source` as a **reference** to a shared source *document*: `{:name
  <source-cid> :major :locator}`. The client sends a raw `{:source-id :locator}` (source-id =
  the source work's cid); a blank source-id clears the reference; an already-normalized ref
  (carried forward on a new minor) is kept. So changing which source is cited, or its locator,
  is a new version — while the source *work* itself is a shared, independently-versioned
  document (see landing.agora.source)."
  [content]
  (let [src (:source content)]
    (cond
      (nil? src) content
      (:name src) content                                       ;; already a ref (carried forward)
      (str/blank? (:source-id src)) (assoc content :source nil)  ;; explicit clear
      :else (assoc content :source {:name (:source-id src)
                                    :major 1
                                    :locator (:locator src)}))))

(def ^:private carried
  "Immutable content keys carried forward on a new minor (everything authored;
  `:published-at` is re-stamped each version). The legacy `:statement`/`:body` are kept
  so a not-yet-migrated old row carries its prose forward — `normalize-text` then folds
  it into `:text` on write."
  ;; `:source` is the single cited-source **reference** (`{:name :major :locator}`; resolved on
  ;; read). `:year`/`:editor` are the extra fields a `type=source` document carries.
  [:kind :title :text :statement :body :author :owner-id :inputs :source :year :editor])

;; ---------------------------------------------------------------------------
;; Read — the endpoint-facing view (generic across types)
;; ---------------------------------------------------------------------------

(defn view
  "Endpoint view of an already-fetched document `doc`: resolved input refs, successor ids,
  version lineage and translations. `:pins` is dropped; `:owner-id` is renamed to the
  public `:author-id` (the owning account, used to link the author badge to its profile
  page) — nil for unowned/seeded documents. Uniform across types."
  [doc]
  (let [tnlr (domain/tnlr-key doc)
        inputs (domain/input-refs (:inputs doc) (:pins doc))]
    (-> doc
        (assoc :inputs inputs
               :author-id (:owner-id doc)
               ;; resolve the source *reference* to the shared work's display fields
               :source (source/resolve-ref (:source doc) (:lang doc))
               :successors (mapv (fn [sid] {:id sid}) (store/successors-of tnlr))
               :versions (store/versions-of tnlr)
               :translations (store/translations-of (:type doc) (:name doc) (:lang doc)))
        (dissoc :owner-id :pins))))

(defn fetch
  "The document `id` as the endpoint view, or nil if unknown."
  [id]
  (when-let [doc (store/fetch-document id)] (view doc)))

(defn fetch-by-major
  "The latest-minor document of (type, name, major) in `lang` (cross-language
  fallback) — the permanent public identity — or nil."
  [type name major lang]
  (when-let [id (store/resolve-latest-id type name major lang)] (fetch id)))

(defn resolve-successors
  "Enrich a fetched doc's successor ids (`:successors` = `[{:id}…]`) with linkable
  identity `{:id :type :name :lang :major :title}` — used by the server-rendered 'Used by'
  section so the graph's downward edges are crawlable. Each lookup is Caffeine-cached."
  [doc]
  (into []
        (keep (fn [{:keys [id]}]
                (when-let [s (store/fetch-document id)]
                  {:id id
                   :type (:type s)
                   :name (:name s)
                   :lang (:lang s)
                   :major (:major s)
                   :title (:title s)})))
        (:successors doc)))

;; ---------------------------------------------------------------------------
;; Write
;; ---------------------------------------------------------------------------

(defn- inputs-of
  "The inputs for a document: the `[[ki:…]]` citations parsed from its `:text`. Inputs
  are exactly the in-text citations, so an in-text citation is an input edge."
  [_type content _declared lang]
  (domain/cite-refs (or (text-of content) "") lang))

;; `slugify` / `permalink-slug` / `cid-of` live in `landing.agora.document-domain` (cljc)
;; so the SPA builds and resolves identical URLs. `gen-cid` needs the DB (uniqueness), so
;; it stays here.

(def ^:private cid-chars
  "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")

(defn gen-cid
  "A short, opaque, URL-safe identity key — 10 base62 chars (62¹⁰ ≈ 8.4×10¹⁷). A document's
  `name` is a cid: it is NEVER derived from the title, so editing the title never moves it
  and inbound citations/pins (which reference `name`) never dangle. (Seeded documents keep
  their readable stable names — `type-inference`, `aow-1-4` — which serve as their cids just
  as well.)"
  []
  (apply str (repeatedly 10 #(nth cid-chars (rand-int 62)))))

(defn unique-cid
  "A freshly generated cid no document already uses. Collisions in 62¹⁰ are negligible, but
  we check to be safe."
  []
  (loop []
    (let [c (gen-cid)]
      (if (store/cid-taken? c) (recur) c))))

(defn- new-minor!
  "Insert a new minor of the concept `src` with `content` (its declared `:inputs` are
  stored as given), re-pin successors onto it, return the new document view."
  [src content]
  (let [{:keys [type name lang major]} src
        new-id (store/uuid)]
    (store/insert-document! {:id new-id
                             :type type
                             :name name
                             :lang lang
                             :major major
                             :minor (store/next-minor type name lang major)}
                            (normalize-source
                             (normalize-text (assoc content :published-at (store/now-iso)))))
    (store/repin-successors! type name lang major new-id)
    (store/evict-lineage! type name lang major)
    (fetch new-id)))

(defn create
  "Create a brand-new document (major 1, minor 0) of `type`. `content` holds the authored
  fields (`:title`, `:lang`, `:text`, and `:kind` for kind-bearing types). The identity
  `name` is an opaque **cid** (`gen-cid`) — never derived from the title, so it lives in
  URLs, edges and citations **immutably**; editing the title only moves the decorative URL
  slug (see `permalink-slug`), never the cid. Inputs are derived from the `:text` citations.
  Returns the view."
  [type
   owner-id
   {:keys [name lang]
    :as content}]
  (let [id (store/uuid)
        lang (or lang language/default-lang)
        author (store/author-name owner-id)
        name (if (str/blank? name) (unique-cid) name)
        inputs (inputs-of type content (:inputs content) lang)]
    (store/insert-document! {:id id
                             :type type
                             :name name
                             :lang lang
                             :major 1
                             :minor 0}
                            (normalize-source
                             (normalize-text (-> content
                                                 (dissoc :name :lang)
                                                 (assoc :inputs inputs
                                                        :author author
                                                        :owner-id owner-id
                                                        :published-at (store/now-iso))))))
    (store/evict-lineage! type name lang 1)
    (fetch id)))

(defn edit
  "Edit document `id` → a new minor. `changes` overrides authored fields (nils are
  ignored → the old value is kept). Inputs are re-derived from the edited text. nil if
  `id` is unknown."
  [id owner-id changes]
  (when-let [src (store/fetch-document id)]
    (let [merged (merge (select-keys src carried)
                        (into {} (remove (comp nil? val) changes))
                        {:author (store/author-name owner-id)
                         :owner-id owner-id})
          inputs (inputs-of (:type src) merged (:inputs merged) (:lang src))]
      (new-minor! src (assoc merged :inputs inputs)))))

(defn add-input
  "Declare the document referenced by `input` (name+major, same language) as an input
  of `id` → a new minor. Idempotent; `:input-limit` if the cap is exceeded. Works for
  any type (sets inputs explicitly, bypassing body derivation)."
  [id
   owner-id
   {in-name :name
    in-major :major}]
  (when-let [{:keys [type lang]
              :as src}
             (store/fetch-document id)]
    (if (store/resolve-latest-id type in-name in-major lang)
      (let [t {:type type
               :name in-name
               :lang lang
               :major in-major}
            inputs (domain/add-declared (:inputs src) t)]
        (if (> (count inputs) domain/max-inputs)
          :input-limit
          (new-minor! src
                      (merge (select-keys src carried)
                             {:inputs inputs
                              :author (store/author-name owner-id)
                              :owner-id owner-id}))))
      (fetch id))))

(defn drop-input
  "Remove the declared input `input` (name+major) from document `id` → a new minor.
  Inputs are derived from the text, so we also **strip the matching `[[ki:…]]` citation**
  from the text field (keeping its label as plain prose): the inline mention stays
  readable, the edge is gone, and the next edit's re-derive can't bring it back. This is
  what makes removal a first-class input-field action rather than hand-editing the text."
  [id
   owner-id
   {in-name :name
    in-major :major}]
  (when-let [{:keys [type lang]
              :as src}
             (store/fetch-document id)]
    (let [t {:type type
             :name in-name
             :lang lang
             :major in-major}
          text (text-of src)]
      (new-minor! src
                  (cond-> (merge (select-keys src carried)
                                 {:inputs (domain/drop-declared (:inputs src) t)
                                  :author (store/author-name owner-id)
                                  :owner-id owner-id})
                    text (assoc :text (domain/strip-cite text in-name in-major)))))))

(defn translate
  "Create a `to-lang` version of document `id` and to-lang copies of its direct
  inputs. `overrides` (nil-safe) replaces authored fields on the new version.
  Existing versions are untouched. Returns the to-lang document view."
  [id to-lang owner-id overrides]
  (when-let [{:keys [type name major]
              :as src}
             (store/fetch-document id)]
    (when-not (store/lang-exists? type name major to-lang)
      (let [author (store/author-name owner-id)
            declarations
            (mapv (fn [{in-name :name
                        in-major :major
                        in-lang :lang}]
                    (when-not (store/lang-exists? type in-name in-major to-lang)
                      (when-let [s (store/fetch-document
                                    (store/resolve-latest-id type in-name in-major in-lang))]
                        (store/insert-document! {:id (store/uuid)
                                                 :type type
                                                 :name in-name
                                                 :lang to-lang
                                                 :major in-major
                                                 :minor 0}
                                                (merge (select-keys s carried)
                                                       {:inputs []
                                                        :author author
                                                        :owner-id owner-id
                                                        :published-at (store/now-iso)}))
                        (store/evict-lineage! type in-name to-lang in-major)))
                    {:type type
                     :name in-name
                     :lang to-lang
                     :major in-major})
                  (:inputs src))]
        (store/insert-document! {:id (store/uuid)
                                 :type type
                                 :name name
                                 :lang to-lang
                                 :major major
                                 :minor 0}
                                (merge (select-keys src carried)
                                       (into {} (remove (comp nil? val) overrides))
                                       {:inputs declarations
                                        :author author
                                        :owner-id owner-id
                                        :published-at (store/now-iso)}))
        (store/evict-lineage! type name to-lang major)))
    (fetch-by-major type name major to-lang)))

;; ---------------------------------------------------------------------------
;; Discovery / search / admin — all type-parameterised
;; ---------------------------------------------------------------------------

(defn- cite-titles
  "For a card excerpt: each cited KI's current title, as `[{:name <cid> :title …}…]` — a
  JSON-safe vector, NOT a cid-keyed map (JSON would keywordize the cid keys). A flattened
  excerpt can't `humanize` an opaque cid, so it needs the resolved title. Lookups are
  Caffeine-cached and a card cites few KIs, so this is cheap."
  [content lang]
  (into []
        (keep (fn [{:keys [name major]}]
                (when-let [id (store/resolve-latest-id "ki" name major lang)]
                  (when-let [d (store/fetch-document id)]
                    {:name name
                     :title (:title d)}))))
        (domain/cite-refs (or (text-of content) "") lang)))

(defn- card
  "A discovery/search card from a row with a `content` blob — enough to render a preview
  card (identity, kind, title, prose excerpt) and its byline. `:source` is resolved from the
  document's source *reference* to the cited work's display fields, so the card can attribute
  the cited author; when there is none, the byline `:author` is the document's own author.
  `:cite-titles` resolves the excerpt's citations to titles (names are opaque cids)."
  [row]
  (let [c (store/decode-content (:content row))]
    (assoc (select-keys row [:id :type :name :lang :major :minor])
           :kind (:kind c)
           :title (:title c)
           :cite-titles (cite-titles c (:lang row))
           :text (text-of c)
           :author (:author c)
           :author-id (:owner-id c)
           :source (source/resolve-ref (:source c) (:lang row))
           :published-at (:published-at c))))

(defn search
  "Documents of `type` matching `q` in name/title/content, latest minor per lineage,
  scoped to `lang`. Blank `q` → []."
  [type q lang]
  (if (or (nil? q) (empty? q))
    []
    (let [like (str "%" q "%")]
      (mapv
       card
       (store/q!
        db/ds
        ["SELECT k.id, k.type, k.name, k.lang, k.major, k.minor, k.content FROM AGORA_DOCUMENT k
                WHERE k.type = ? AND k.lang = ?
                  AND (k.name LIKE ? OR k.content LIKE ?)
                  AND k.minor = (SELECT MAX(k2.minor) FROM AGORA_DOCUMENT k2
                                 WHERE k2.type = k.type AND k2.name = k.name
                                   AND k2.major = k.major AND k2.lang = k.lang)
                ORDER BY k.name, k.major LIMIT 50"
         type
         lang
         like
         like]
        store/kebab)))))

(defn list-latest
  "Up to 10 latest-minor documents of `type`, sampled at random, scoped to `lang`."
  [type lang]
  (mapv
   card
   (store/q!
    db/ds
    ["SELECT k.id, k.type, k.name, k.lang, k.major, k.minor, k.content FROM AGORA_DOCUMENT k
            WHERE k.type = ? AND k.lang = ?
              AND k.minor = (SELECT MAX(k2.minor) FROM AGORA_DOCUMENT k2
                             WHERE k2.type = k.type AND k2.name = k.name
                               AND k2.major = k.major AND k2.lang = k.lang)
            ORDER BY RAND() LIMIT 10"
     type
     lang]
    store/kebab)))

(defn list-recent
  "Latest-minor documents of `type` scoped to `lang`, most recent first (the recent
  documents feed). published-at lives in the content blob, so sort after decoding."
  [type lang]
  (->>
    (store/q!
     db/ds
     ["SELECT a.id, a.type, a.name, a.lang, a.major, a.minor, a.content FROM AGORA_DOCUMENT a
           WHERE a.type = ? AND a.lang = ?
             AND a.minor = (SELECT MAX(a2.minor) FROM AGORA_DOCUMENT a2
                            WHERE a2.type = a.type AND a2.name = a.name
                              AND a2.major = a.major AND a2.lang = a.lang)"
      type
      lang]
     store/kebab)
    (mapv card)
    (sort-by :published-at #(compare %2 %1))
    vec))

(defn- owned-by
  "Latest-minor documents in `lang` OWNED by account `author-id` (its own claims).
  `owner-id` lives in the content blob → `content LIKE` prefilter, confirmed on decode."
  [author-id lang]
  (->>
    (store/q!
     db/ds
     ["SELECT k.id, k.type, k.name, k.lang, k.major, k.minor, k.content
            FROM AGORA_DOCUMENT k
           WHERE k.lang = ? AND k.content LIKE ?
             AND k.minor = (SELECT MAX(k2.minor) FROM AGORA_DOCUMENT k2
                            WHERE k2.type = k.type AND k2.name = k.name
                              AND k2.major = k.major AND k2.lang = k.lang)"
      lang
      (str "%" author-id "%")]
     store/kebab)
    (filter #(= author-id (:owner-id (store/decode-content (:content %)))))
    (mapv card)))

(defn- sourced-by
  "Latest-minor documents in `lang` that CITE a source authored by `author-id` — works
  that *quote* this person (as opposed to `owned-by`'s own claims). A source is now a
  document owned by its author, so this finds the author's source cids (`source/names-of-author`)
  then the non-source documents whose `content.:source` reference points at one of them. This
  is why a cited figure (e.g. Sun Tzu) has a populated profile even though they claim nothing."
  [author-id lang]
  (let [src-names (source/names-of-author author-id)]
    (if (empty? src-names)
      []
      (->>
        (store/q!
         db/ds
         ["SELECT k.id, k.type, k.name, k.lang, k.major, k.minor, k.content
                FROM AGORA_DOCUMENT k
               WHERE k.lang = ? AND k.type <> 'source'
                 AND k.minor = (SELECT MAX(k2.minor) FROM AGORA_DOCUMENT k2
                                WHERE k2.type = k.type AND k2.name = k.name
                                  AND k2.major = k.major AND k2.lang = k.lang)"
          lang]
         store/kebab)
        (filter (fn [r]
                  (contains? src-names (:name (:source (store/decode-content (:content r)))))))
        (mapv card)))))

(defn by-author
  "Everything attributed to person `author-id` in `lang`: documents they OWN (their own
  claims) plus documents that CITE them as a source author (works that quote them),
  latest minor per lineage, deduped by id, most recent first — plus `:last-activity`."
  [author-id lang]
  (let [all (->> (concat (owned-by author-id lang) (sourced-by author-id lang))
                 (reduce (fn [m c] (assoc m (:id c) c)) {}) ; dedupe by id
                 vals
                 (sort-by :published-at #(compare %2 %1))
                 vec)]
    {:documents all
     :last-activity (->> all
                         (keep :published-at)
                         sort
                         last)}))

(defn list-tnrs
  "All lineages (name, major) of `type` with version/language/latest counts (admin)."
  [type]
  (store/q!
   db/ds
   ["SELECT name, major, COUNT(*) AS versions, COUNT(DISTINCT lang) AS langs, MAX(minor) AS latest
      FROM AGORA_DOCUMENT WHERE type = ? GROUP BY name, major ORDER BY name, major"
    type]
   store/kebab))

(defn all-tnrs
  "Every lineage of every type (all types), **one row per language version** —
  (type, name, lang, major) with its version count, latest minor, and the latest minor's
  `:title` (the human heading the admin table shows in place of the identity slug). One
  query: a self-join pins each lineage's latest-minor row, whose content yields the title."
  []
  (->> (store/q!
        db/ds
        ["SELECT d.type, d.name, d.lang, d.major, d.content, g.versions, g.latest
            FROM AGORA_DOCUMENT d
            JOIN (SELECT type, name, lang, major, COUNT(*) AS versions, MAX(minor) AS latest
                    FROM AGORA_DOCUMENT
                   GROUP BY type, name, lang, major) g
              ON d.type = g.type AND d.name = g.name AND d.lang = g.lang
                 AND d.major = g.major AND d.minor = g.latest
           ORDER BY d.type, d.name, d.major, d.lang"]
        store/kebab)
       (mapv (fn [{:keys [content] :as r}]
               (-> r
                   (assoc :title (:title (store/decode-content content)))
                   (dissoc :content))))))

(defn delete-tnr!
  "Drop a single language version of a lineage — (type, name, lang, major) — plus its
  successor-index rows (targeted, so no full rebuild). Returns rows removed."
  [type doc-name lang doc-major]
  (store/q!
   db/ds
   ["DELETE FROM AGORA_SUCCESSOR
      WHERE (input_type = ? AND input_name = ? AND input_lang = ? AND input_major = ?)
         OR successor_id IN (SELECT id FROM AGORA_DOCUMENT
                             WHERE type = ? AND name = ? AND lang = ? AND major = ?)"
    type
    doc-name
    lang
    doc-major
    type
    doc-name
    lang
    doc-major])
  (let [n (:next.jdbc/update-count
           (store/q1!
            db/ds
            ["DELETE FROM AGORA_DOCUMENT WHERE type = ? AND name = ? AND lang = ? AND major = ?"
             type
             doc-name
             lang
             doc-major]))]
    (store/clear-caches!)
    n))

(defn compact-tnr!
  "Keep only the latest minor of one language version — (type, name, lang, major) —
  and delete the rest. Returns rows removed."
  [type doc-name lang doc-major]
  (let
    [latest
     (:latest
      (store/q1!
       db/ds
       ["SELECT MAX(minor) AS latest FROM AGORA_DOCUMENT
                            WHERE type = ? AND name = ? AND lang = ? AND major = ?"
        type
        doc-name
        lang
        doc-major]
       store/kebab))
     n
     (or
      (:next.jdbc/update-count
       (store/q1!
        db/ds
        ["DELETE FROM AGORA_DOCUMENT
                            WHERE type = ? AND name = ? AND lang = ? AND major = ? AND minor < ?"
         type
         doc-name
         lang
         doc-major
         latest]))
      0)]
    (store/clear-caches!)
    n))

(defn sitemap-rows
  "Every public permalink — the crawlable document types — as {:type :name :major :lang
  :lastmod}, one row per lineage (type, name, lang, major). `type='source'` is **excluded**:
  sources are shared works with no public permalink shell of their own. `lastmod` is the
  lineage's latest publication date (`MAX(published_at)`, an ISO-8601 string that sorts
  chronologically), so a crawler re-fetches a permalink only when its current version changed.
  Pure SQL over the denormalized `published_at` column — no content decode — so it scales and
  can later be keyset-paginated into a chunked sitemap index."
  []
  (->> (store/q! db/ds
                 ["SELECT d.type, d.name, d.major, d.lang, d.content, g.lastmod
                     FROM AGORA_DOCUMENT d
                     JOIN (SELECT type, name, major, lang, MAX(minor) AS latest,
                                  MAX(published_at) AS lastmod
                             FROM AGORA_DOCUMENT
                            WHERE type <> 'source'
                            GROUP BY type, name, major, lang) g
                       ON d.type = g.type AND d.name = g.name AND d.major = g.major
                          AND d.lang = g.lang AND d.minor = g.latest"]
                 store/kebab)
       (mapv (fn [{:keys [content] :as r}]
               (-> r
                   (assoc :title (:title (store/decode-content content)))
                   (dissoc :content))))))

(defn rebuild-successor-index!
  "Recompute AGORA_SUCCESSOR from every document's declared inputs (all types), dropping
  the in-memory caches. Delegates to the shared storage layer; run daily."
  []
  (store/rebuild-successor-index!))

(defn consistency-issues
  "Scan **every version** of every document for reference problems (see the *Consistency
  rules* in agora/CLAUDE.md):

   - **`:broken`** — a **dangling reference**: an input edge / in-text `[[ki:…]]`
     citation pointing at a lineage that does not exist (in any language). Inputs and
     citations are the same thing under the model, so this catches a non-existing input
     *and* a non-existing quote.
   - **`:self`** — a **self-reference**: the document cites its own lineage
     (same type + name + major). A document cannot be an input of itself — that is a
     degenerate cycle — so it is reported even though the target exists.

  Runs over current *and* former versions. Returns a vector of
  {:id :type :name :lang :major :minor :title :broken […] :self […]}, each ref as
  {:name :major :lang}; `:id` pins the exact version so the admin can deep-link to it."
  []
  (let [;; Every existing identity (any minor, any language) as [type name major] —
        ;; a referenced lineage is live iff it appears here (matches the engine's
        ;; cross-language fallback). Fetched ONCE and checked in memory, so the scan is
        ;; two queries total rather than one per reference (which floods the DB pool).
        existing (into #{}
                       (map (juxt :type :name :major))
                       (store/q! db/ds
                                 ["SELECT DISTINCT type, name, major FROM AGORA_DOCUMENT"]
                                 store/kebab))
        ;; every ref carries its own :type (inputs are typed TNLRs; in-text citations
        ;; resolve to typed refs), so identity is [type name major]
        ref-id (fn [r] [(:type r) (:name r) (:major r)])]
    (->> (store/q! db/ds
                   ["SELECT id, type, name, lang, major, minor, content FROM AGORA_DOCUMENT"]
                   store/kebab)
         (keep
          (fn [row]
            (let [c (store/decode-content (:content row))
                  lang (:lang row)
                  text (text-of c)
                  self [(:type row) (:name row) (:major row)]
                  refs (distinct (concat (:inputs c) (domain/cite-refs (or text "") lang)))
                  broken (->> refs
                              (remove (fn [r] (existing (ref-id r))))
                              (map #(select-keys % [:name :major :lang]))
                              distinct
                              vec)
                  self-refs (->> refs
                                 (filter (fn [r] (= (ref-id r) self)))
                                 (map #(select-keys % [:name :major :lang]))
                                 distinct
                                 vec)]
              (when (or (seq broken) (seq self-refs))
                (assoc (select-keys row [:id :type :name :lang :major :minor])
                       :title (:title c)
                       :broken broken
                       :self self-refs)))))
         vec)))
