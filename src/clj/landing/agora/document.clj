(ns landing.agora.document
  "The single engine for versioned AGORA_DOCUMENT documents. Every document type is a
  `type` row here, sharing one implementation: create / edit / fetch, explicit inputs
  (add / drop), translate, search, discovery, admin and sitemap are all generic — the
  engine has no per-type behaviour of its own, so it never branches on a specific type.

  All graph decisions live in the domain; this ns only does the generic I/O on top of the
  shared storage primitives (landing.agora.document.store)."
  (:require
   [clojure.string                  :as str]
   [landing.agora.db                :as db]
   [landing.agora.document.db-store :as dbs]
   [landing.agora.document.identity :as di]
   [landing.agora.document.lineage  :as lineage]
   [landing.agora.document.store    :as store]
   [landing.agora.source            :as source]
   [landing.language                :as language]))

;; Every document's prose lives under one content key, `:text`. A document's inputs ARE the
;; `[[ki:…]]` citations in that text (parsed on write) — an in-text citation is an input edge.

(defn- normalize-source
  "Shape `content.:source` on write: it is a **reference to a shared source** (`AGORA_SOURCE`),
  `{:source-id :locator}` — the source id plus this `kind=source` KI's own locator. The frontend's
  `strip-source` already reduces it to that and sends `{:source-id \"\"}` as the explicit **clear**
  sentinel, turned into `:source nil` here (so a cleared source doesn't persist an empty-id ref).
  The source's fields live in `AGORA_SOURCE`, not the content, so many source-KIs share one source
  (see landing.agora.source)."
  [content]
  (let [src (:source content)]
    (cond
      (nil? src) content
      (str/blank? (:source-id src)) (assoc content :source nil) ;; explicit clear
      :else (assoc content :source (select-keys src [:source-id :locator])))))

;; ---------------------------------------------------------------------------
;; Read — the endpoint-facing view (generic across types)
;; ---------------------------------------------------------------------------

(defn- input-doc
  "Fetch the document an input ref points at — by its pinned `:id` if resolved, else by
  resolving (type, name, major) in `lang`. nil if unknown."
  [{:keys [id type name major]} lang]
  (when-let [id (or id (store/resolve-latest-id (or type :ki) name major lang))]
    (store/fetch-document id)))

(defn- quote-author-name
  "The work-author of a document's first `kind=source` input — the person a quoting KI's
  statement is attributed to (`document.kind/attributed-author`). nil when it quotes none. Used by the
  discovery `card` (which has bare input TNLRs, not the split `:quotes`)."
  [inputs lang]
  (some (fn [inp]
          (when-let [d (input-doc inp lang)]
            (when (= "source" (:kind d)) (:author-name (source/resolve-ref (:source d))))))
        inputs))

(defn- split-inputs
  "Partition a document's resolved input refs into `{:inputs :quotes}`. A `kind=source` input
  is a **quote** — not a normal in-prose citation — so it is pulled out and resolved to its
  quotation + shared work `{:name :major :id :title :author-name :author-id :locator}` for the
  UI (display + re-edit). The rest stay as `:inputs` (the in-text citations)."
  [inputs lang]
  (reduce (fn [acc inp]
            (let [d (input-doc inp lang)]
              (if (= "source" (:kind d))
                (let [work (source/resolve-ref (:source d))]
                  (update acc
                          :quotes
                          conj
                          {:name (:name inp)
                           :major (:major inp)
                           :id (:id inp)
                           :title (:title d)
                           :author-name (:author-name work)
                           :author-id (:author-id work)
                           :locator (:locator work)}))
                (update acc :inputs conj inp))))
          {:inputs []
           :quotes []}
          inputs))

(defn- successor-refs
  "Distinct successor lineages of `tnlr`, each as `{:id <latest-live-minor-id>}`. Resolves each
  cached successor id, **dropping dangling ones** (a `successor_id` whose document was deleted —
  e.g. an old minor removed by `compact-tnr!`) and collapsing the several indexed minors of one
  lineage to a single entry at its latest minor. Keeps the read robust against successor-cache
  drift (a dangling row otherwise renders as a broken/'missing' successor in the SPA)."
  [tnlr]
  (->> (store/successors-of tnlr)
       (keep store/fetch-document)
       (remove :draft) ; an unpublished successor stays hidden until it is published
       (group-by (juxt :type :name :lang :major))
       vals
       (mapv (fn [ds] {:id (:id (lineage/latest-with-drafts ds))}))))

(defn view
  "Endpoint view of an already-fetched document `doc`: resolved input refs, successor ids,
  version lineage and translations. `:pins` is dropped; `:owner-id` is renamed to the
  public `:author-id` (the owning account, used to link the author badge to its profile
  page) — nil for unowned/seeded documents. `kind=source` inputs are split out as `:quotes`
  (with their work-author) and the statement is attributed to the first quote's author
  (`:quote-author-name`). Uniform across types."
  [doc]
  (let [tnlr (di/tnlr-key doc)
        {:keys [inputs quotes]} (split-inputs (di/input-refs (:inputs doc) (:pins doc))
                                              (:lang doc))]
    (-> doc
        (assoc :inputs inputs
               :quotes quotes
               :quote-author-name (:author-name (first quotes))
               :author-id (:owner-id doc)
               ;; resolve the source *reference* to the shared work's display fields
               :source (source/resolve-ref (:source doc))
               :successors (successor-refs tnlr)
               :versions (store/documents (:type doc) (:name doc) (:lang doc) (:major doc))
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

(defn fetch-by-major-in-publication
  "The document of (type, name, language, major) resolved **within publication `pub-id`** — the
  publication's own draft of the lineage if it has one, else the latest published minor. The
  publication-scoped counterpart of `fetch-by-major`; nil if it resolves to nothing."
  [pub-id type name major lang]
  (when-let [id (store/resolve-in-publication pub-id type name major lang)] (fetch id)))

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

;; A document's inputs are derived from its content (the `[[ki:…]]` citations in the text, plus any
;; edge-only source `:quotes`) by `landing.agora.document.lineage/inputs-of` — the single home of
;; that rule, shared with the storage-free corpus. This engine calls it on create/edit.

;; `slugify` / `permalink-slug` / `cid-of` live in `landing.agora.document.identity` (cljc)
;; so the SPA builds and resolves identical URLs. `gen-cid` needs the DB (uniqueness), so
;; it stays here.

(def ^:private cid-chars "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")

(defn gen-cid
  "A short, opaque, URL-safe identity key — 10 base62 chars (62¹⁰ ≈ 8.4×10¹⁷). A document's
  `name` is a cid: it is NEVER derived from the title, so editing the title never moves it
  and inbound citations/pins (which reference `name`) never dangle. (Seeded documents keep
  their readable stable names — e.g. `type-inference` — which serve as their cids just
  as well.)"
  []
  (apply str (repeatedly 10 #(nth cid-chars (rand-int 62)))))

(defn unique-cid
  "A freshly generated cid no document already uses. Collisions in 62¹⁰ are negligible, but
  we check to be safe."
  []
  (loop [] (let [c (gen-cid)] (if (store/cid-taken? c) (recur) c))))

(defn- new-minor!
  "Insert a new **draft** minor of the concept `src` with `content` (its declared `:inputs`
  are stored as given) and return the new document view. Successors are **not** re-pinned:
  a draft isn't the resolved version, so edges keep pointing at the current published minor
  until `publish!` promotes this one."
  [src content publication-id]
  (let [{:keys [type name lang major]} src
        new-id (dbs/uuid)]
    (store/insert-document! {:id new-id
                             :type type
                             :name name
                             :lang lang
                             :major major
                             :minor (store/next-minor type name lang major)
                             ;; every edit lands as a draft until Publish
                             :draft? true
                             :publication-id publication-id}
                            (normalize-source (assoc content :published-at (dbs/now-iso))))
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
    :as content}
   publication-id]
  (let [id (dbs/uuid)
        lang (or lang language/default-lang)
        author (store/author-name owner-id)
        name (if (str/blank? name) (unique-cid) name)
        inputs (lineage/inputs-of content lang)]
    (store/insert-document!
     {:id id
      :type type
      :name name
      :lang lang
      :major 1
      :minor 0
      ;; a newly created document starts as a draft until Publish
      :draft? true
      :publication-id publication-id}
     (normalize-source
      (-> content
          ;; `:quotes` is transient — it is folded into
          ;; `:inputs`
          (dissoc :name :lang :quotes)
          (assoc :inputs inputs :author author :owner-id owner-id :published-at (dbs/now-iso)))))
    (store/evict-lineage! type name lang 1)
    (fetch id)))

(defn edit
  "Edit document `id` → a new minor. `changes` overrides authored fields (nils are
  ignored → the old value is kept). Inputs are re-derived from the edited text. nil if
  `id` is unknown."
  [id owner-id changes publication-id]
  (when-let [src (store/fetch-document id)]
    (let [merged (merge (select-keys src lineage/carried)
                        (into {} (remove (comp nil? val) changes))
                        {:author (store/author-name owner-id)
                         :owner-id owner-id})
          inputs (lineage/inputs-of merged (:lang src))]
      (new-minor! src
                  (-> merged
                      (assoc :inputs inputs)
                      (dissoc :quotes))
                  publication-id))))

(defn add-input
  "Declare the document referenced by `input` (name+major, same language) as an input
  of `id` → a new minor. Idempotent; `:input-limit` if the cap is exceeded. Works for
  any type (sets inputs explicitly, bypassing body derivation)."
  [id
   owner-id
   {in-name :name
    in-major :major
    in-type :type}
   publication-id]
  (when-let [{:keys [lang]
              :as src}
             (store/fetch-document id)]
    (let [ty (or in-type :ki)]
      (if (store/resolve-latest-id ty in-name in-major lang)
        (let [t {:type ty
                 :name in-name
                 :lang lang
                 :major in-major}
              inputs (di/add-declared-input (:inputs src) t)]
          (if (> (count inputs) di/max-inputs)
            :input-limit
            (new-minor! src
                        (merge (select-keys src lineage/carried)
                               {:inputs inputs
                                :author (store/author-name owner-id)
                                :owner-id owner-id})
                        publication-id)))
        (fetch id)))))

(defn drop-input
  "Remove the declared input `input` (name+major) from document `id` → a new minor.
  Inputs are derived from the text, so we also **strip the matching `[[ki:…]]` citation**
  from the text field (keeping its label as plain prose): the inline mention stays
  readable, the edge is gone, and the next edit's re-derive can't bring it back. This is
  what makes removal a first-class input-field action rather than hand-editing the text."
  [id
   owner-id
   {in-name :name
    in-major :major
    in-type :type}
   publication-id]
  (when-let [{:keys [lang]
              :as src}
             (store/fetch-document id)]
    ;; A dropped input is the cited document, so its TNLR (and the citation stripped from the text)
    ;; carry the *input's* type — threaded from the request, defaulting to `:ki` — not the editing
    ;; doc's type.
    (let [ty (or in-type :ki)
          t {:type ty
             :name in-name
             :lang lang
             :major in-major}
          text (:text src)]
      (new-minor! src
                  (cond-> (merge (select-keys src lineage/carried)
                                 {:inputs (di/drop-declared-input (:inputs src) t)
                                  :author (store/author-name owner-id)
                                  :owner-id owner-id})
                    text (assoc :text (di/strip-cite text t)))
                  publication-id))))

(defn translate
  "Create a `to-lang` version of document `id` and to-lang copies of its direct
  inputs. `overrides` (nil-safe) replaces authored fields on the new version.
  Existing versions are untouched. Returns the to-lang document view."
  [id to-lang owner-id overrides publication-id]
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
                        (store/insert-document! {:id (dbs/uuid)
                                                 :type type
                                                 :name in-name
                                                 :lang to-lang
                                                 :major in-major
                                                 :minor 0
                                                 :draft? true
                                                 :publication-id publication-id}
                                                (merge (select-keys s lineage/carried)
                                                       {:inputs []
                                                        :author author
                                                        :owner-id owner-id
                                                        :published-at (dbs/now-iso)}))
                        (store/evict-lineage! type in-name to-lang in-major)))
                    {:type type
                     :name in-name
                     :lang to-lang
                     :major in-major})
                  (:inputs src))]
        (store/insert-document!
         {:id (dbs/uuid)
          :type type
          :name name
          :lang to-lang
          :major major
          :minor 0
          ;; a translation lands as a draft (with its input siblings) until Publish
          :draft? true
          :publication-id publication-id}
         ;; keep only content keys from `overrides` — the caller passes
         ;; the whole request body, whose `:lang` is the *target* language
         ;; (identity), not content, and must not leak into the blob
         (merge (select-keys src lineage/carried)
                (into {} (remove (comp nil? val) (select-keys overrides lineage/carried)))
                {:inputs declarations
                 :author author
                 :owner-id owner-id
                 :published-at (dbs/now-iso)}))
        (store/evict-lineage! type name to-lang major)))
    (fetch-by-major type name major to-lang)))

(defn- pending-inputs
  "The declared `inputs` of a document that have **no published version** (draft-only lineages) —
  `publish!` refuses until they are published, so a public node never depends on a hidden one. The
  pending *set* is the domain rule (`lineage/pending?`, given a SQL `published?` predicate); each
  entry is enriched here with the input's latest (draft) version id + title so the UI can link it."
  [inputs]
  (->> (lineage/pending? inputs
                         (fn [{:keys [type name major lang]}]
                           (some? (store/resolve-latest-id type name major lang))))
       (mapv (fn [{:keys [type name major lang]}]
               (let [latest (last (store/documents type name lang major))
                     d (some-> (:id latest)
                               store/fetch-document)]
                 {:type type
                  :name name
                  :major major
                  :lang lang
                  :id (:id latest)
                  :title (:title d)})))))

(defn publish!
  "Publish document version `id` on behalf of `user-id` — who must **own** it. Refuses while any
  input is still a draft (referential integrity: a public node may not depend on a hidden one),
  returning `{:unpublished-inputs [...]}`. Otherwise clears the draft flag, prunes the lineage's
  intermediate drafts and re-pins successors onto it (see `store/publish!`). Returns the published
  view, `{:unpublished-inputs …}` when blocked, `:forbidden` when `user-id` is not the owner, or
  nil when `id` is unknown."
  [id user-id]
  (when-let [{:keys [type name lang major inputs]
              doc-owner :owner-id}
             (store/fetch-document id)]
    (cond
      (not= user-id doc-owner) :forbidden
      :else (let [pending (pending-inputs inputs)]
              (if (seq pending)
                {:unpublished-inputs pending}
                (do (store/publish! type name lang major id) (fetch id)))))))

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
                (when-let [id (store/resolve-latest-id :ki name major lang)]
                  (when-let [d (store/fetch-document id)]
                    {:name name
                     :title (:title d)}))))
        (di/cite-refs (or (:text content) "") lang)))

(defn- card
  "A discovery/search card from a row with a `content` blob — enough to render a preview
  card (identity, kind, title, prose excerpt) and its byline. `:source` is resolved from the
  document's source *reference* to the cited work's display fields, so the card can attribute
  the cited author; when there is none, the byline `:author` is the document's own author.
  `:cite-titles` resolves the excerpt's citations to titles (names are opaque cids)."
  [row]
  (let [c (dbs/decode-content (:content row))]
    (assoc (select-keys row [:id :type :name :lang :major :minor])
           :draft (dbs/truthy? (:draft row))
           :kind (:kind c)
           :title (:title c)
           :cite-titles (cite-titles c (:lang row))
           :text (:text c)
           :author (:author c)
           :author-id (:owner-id c)
           :source (source/resolve-ref (:source c))
           :quote-author-name (quote-author-name (:inputs c) (:lang row))
           :published-at (:published-at c))))

(defn cards-in-publication
  "Discover-style cards for the documents a publication created — one per lineage at its latest
  tagged minor (drafts included), newest first — so the publication page renders like discover."
  [pub-id]
  (->>
    (dbs/q!
     db/ds
     ["SELECT id, type, name, lang, major, minor, draft, content, published_at
                    FROM AGORA_DOCUMENT WHERE publication_id = ?"
      pub-id]
     dbs/kebab)
    (group-by (juxt :type :name :lang :major))
    vals
    (mapv (comp card lineage/latest-with-drafts))
    (sort-by :published-at #(compare %2 %1))
    vec))

(defn search
  "Documents of `type` matching `q` in name/title/content, latest minor per lineage,
  scoped to `lang`. Blank `q` → []."
  [type q lang]
  (if (or (nil? q) (empty? q))
    []
    (let [like (str "%" q "%")]
      (mapv
       card
       (dbs/q!
        db/ds
        ["SELECT k.id, k.type, k.name, k.lang, k.major, k.minor, k.content FROM AGORA_DOCUMENT k
                WHERE k.type = ? AND k.lang = ? AND k.draft = 0
                  AND (k.name LIKE ? OR k.content LIKE ?)
                  AND k.minor = (SELECT MAX(k2.minor) FROM AGORA_DOCUMENT k2
                                 WHERE k2.type = k.type AND k2.name = k.name
                                   AND k2.major = k.major AND k2.lang = k.lang AND k2.draft = 0)
                ORDER BY k.name, k.major LIMIT 50"
         type
         lang
         like
         like]
        dbs/kebab)))))

(defn list-recent
  "Latest-minor documents of `type` in `lang`, most recent first (the discover feed). By
  default **published only**; with `include-drafts?` the latest minor per lineage shows even
  when it's a draft (and draft-only lineages appear) — each card carries `:draft` so the UI can
  flag it and link it by exact-version URL. published-at lives in the content blob, so sort
  after decoding."
  ([type lang] (list-recent type lang false))
  ([type lang include-drafts?]
   (let [df (if include-drafts? "" " AND a.draft = 0")
         df2 (if include-drafts? "" " AND a2.draft = 0")]
     (->>
       (dbs/q!
        db/ds
        [(str
          "SELECT a.id, a.type, a.name, a.lang, a.major, a.minor, a.draft, a.content
                 FROM AGORA_DOCUMENT a
                WHERE a.type = ? AND a.lang = ?"
          df
          "
                  AND a.minor = (SELECT MAX(a2.minor) FROM AGORA_DOCUMENT a2
                                 WHERE a2.type = a.type AND a2.name = a.name
                                   AND a2.major = a.major AND a2.lang = a.lang"
          df2
          ")")
         type
         lang]
        dbs/kebab)
       (mapv card)
       (sort-by :published-at #(compare %2 %1))
       vec))))

(defn- owned-by
  "Latest-minor documents in `lang` OWNED by account `author-id` (its own claims) — **including
  the owner's unpublished drafts** (flagged `:draft` on the card), so an author's profile is
  where they find and publish their work in progress. `owner-id` lives in the content blob →
  `content LIKE` prefilter, confirmed on decode."
  [author-id lang]
  (->>
    (dbs/q!
     db/ds
     ["SELECT k.id, k.type, k.name, k.lang, k.major, k.minor, k.draft, k.content
            FROM AGORA_DOCUMENT k
           WHERE k.lang = ? AND k.content LIKE ?
             AND k.minor = (SELECT MAX(k2.minor) FROM AGORA_DOCUMENT k2
                            WHERE k2.type = k.type AND k2.name = k.name
                              AND k2.major = k.major AND k2.lang = k.lang)"
      lang
      (str "%" author-id "%")]
     dbs/kebab)
    (filter #(= author-id (:owner-id (dbs/decode-content (:content %)))))
    (mapv card)))

(defn- sourced-by
  "Latest-minor `kind=source` KIs in `lang` whose **source** is authored by `author-id` — the
  quotations *from this person's works* (as opposed to `owned-by`'s own claims). A person
  authors **sources** (`AGORA_SOURCE`); each source maps to many `kind=source` KIs (via
  `content.:source.:source-id`). This is why a cited figure (e.g. Sun Tzu) has a populated
  profile even though they claim nothing. Joins the source-KIs to the author's sources by a
  `content LIKE '%source-id%'` prefilter, confirmed on the decoded `:source` ref."
  [author-id lang]
  (let [source-ids (into #{}
                         (map :id)
                         (dbs/q! db/ds
                                 ["SELECT id FROM AGORA_SOURCE WHERE person_id = ?" author-id]
                                 dbs/kebab))]
    (if (empty? source-ids)
      []
      (->>
        (dbs/q!
         db/ds
         ["SELECT DISTINCT k.id, k.type, k.name, k.lang, k.major, k.minor, k.content
                FROM AGORA_DOCUMENT k
                JOIN AGORA_SOURCE s
                  ON s.person_id = ? AND k.content LIKE CONCAT('%', s.id, '%')
               WHERE k.lang = ? AND k.draft = 0
                 AND k.minor = (SELECT MAX(k2.minor) FROM AGORA_DOCUMENT k2
                                WHERE k2.type = k.type AND k2.name = k.name
                                  AND k2.major = k.major AND k2.lang = k.lang AND k2.draft = 0)"
          author-id
          lang]
         dbs/kebab)
        (filter (fn [r] (source-ids (:source-id (:source (dbs/decode-content (:content r)))))))
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

(defn all-tnrs
  "Every lineage of every type (all types), **one row per language version** —
  (type, name, lang, major) with its version count, latest minor, and the latest minor's
  `:title` and epistemic `:kind` (the human heading + kind badge the admin table shows in
  place of the identity slug). The raw query is `dbs/all-tnr-rows`; here we decode each
  latest-minor's content into those display fields."
  []
  (mapv (fn [{:keys [content]
              :as r}]
          (let [c (dbs/decode-content content)]
            (-> r
                (assoc :title (:title c) :kind (:kind c))
                (dissoc :content))))
        (dbs/all-tnr-rows)))

(defn delete-tnr!
  "Drop a single language version of a lineage (admin) via `dbs/delete-lineage!`, then invalidate
  the caches. Returns rows removed."
  [type doc-name lang doc-major]
  (let [n (dbs/delete-lineage! type doc-name lang doc-major)]
    (store/clear-caches!)
    n))

(defn compact-tnr!
  "Keep only the latest minor of one language version (admin) via `dbs/compact-lineage!`, then
  invalidate the caches. Returns rows removed."
  [type doc-name lang doc-major]
  (let [n (dbs/compact-lineage! type doc-name lang doc-major)]
    (store/clear-caches!)
    n))

(defn sitemap-rows
  "Every public permalink — all document types (KIs, incl. `kind=source` quotations, and
  articles) — as {:type :name :major :lang :lastmod}, one row per lineage (type, name, lang,
  major). `lastmod` is the lineage's latest publication date (`MAX(published_at)`, an ISO-8601
  string that sorts chronologically), so a crawler re-fetches a permalink only when its current
  version changed. Pure SQL over the denormalized `published_at` column — no content decode —
  so it scales and can later be keyset-paginated into a chunked sitemap index."
  []
  (->>
    (dbs/q!
     db/ds
     ["SELECT d.type, d.name, d.major, d.lang, d.content, g.lastmod
                     FROM AGORA_DOCUMENT d
                     JOIN (SELECT type, name, major, lang, MAX(minor) AS latest,
                                  MAX(published_at) AS lastmod
                             FROM AGORA_DOCUMENT
                            WHERE draft = 0
                            GROUP BY type, name, major, lang) g
                       ON d.type = g.type AND d.name = g.name AND d.major = g.major
                          AND d.lang = g.lang AND d.minor = g.latest
                    WHERE d.draft = 0"]
     dbs/kebab)
    (mapv (fn [{:keys [content]
                :as r}]
            (-> r
                (assoc :title (:title (dbs/decode-content content)))
                (dissoc :content))))))

(defn rebuild-successor-index!
  "Recompute AGORA_SUCCESSOR from every document's declared inputs (all types), dropping
  the in-memory caches. Delegates to the shared storage layer; run daily."
  []
  (store/rebuild-successor-index!))

(defn rebuild-pins!
  "Recompute every document's `computed.:pins` from its declared inputs, healing pin drift.
  Delegates to the storage layer; run daily. Returns the number of documents re-pinned."
  []
  (store/rebuild-pins!))

(defn- successor-cache-issues
  "Successor-cache drift: `AGORA_SUCCESSOR` rows whose `successor_id` no longer exists in
  `AGORA_DOCUMENT` (e.g. an old minor deleted by compaction whose row wasn't cleaned). Grouped
  by the **input (cited) lineage** the stale rows hang off, resolved to that lineage's current
  version so the admin can deep-link to it. Each →
  {:type :name :lang :major (:minor :title) :dangling-successors [dead-id…]}. A `rebuild-successor-index!`
  heals them; this surfaces them so the drift is visible rather than silent."
  []
  (->>
    (dbs/q!
     db/ds
     ["SELECT s.input_type AS type, s.input_name AS name, s.input_lang AS lang,
                          s.input_major AS major, s.successor_id AS successor_id
                     FROM AGORA_SUCCESSOR s
                LEFT JOIN AGORA_DOCUMENT d ON d.id = s.successor_id
                    WHERE d.id IS NULL"]
     dbs/kebab)
    (group-by (juxt :type :name :lang :major))
    (mapv (fn [[[t n l mj] rows]]
            (let [doc (when-let [id (store/resolve-latest-id t n mj l)] (store/fetch-document id))]
              (cond-> {:type t
                       :name n
                       :lang l
                       :major mj
                       :dangling-successors (mapv :successor-id rows)}
                doc (assoc :minor (:minor doc) :title (:title doc))))))))

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
   - **`:dangling-successors`** — **successor-cache drift**: the reverse-edge cache
     (`AGORA_SUCCESSOR`) still points at a deleted document version (see
     `successor-cache-issues`), reported against the cited lineage.

  (A source having inputs is not scanned for — it is structurally impossible: `lineage/inputs-of`
  forces a `type=source` document's inputs to `[]`, so the quotation feature never applies.)

  Runs over current *and* former versions. Returns a vector of
  {:id :type :name :lang :major :minor :title :broken […] :self […]}, each ref as
  {:name :major :lang}; `:id` pins the exact version so the admin can deep-link to it."
  []
  ;; Persistence supplies the data — the set of live lineages and every version — in two queries;
  ;; the broken/self rule is pure (`lineage/consistency-issues`), applied over that data. Raw rows
  ;; carry the `type` column as a string; the domain works in keyword types, so keyword it here (a
  ;; DB-read boundary) before it meets the rule. Two queries total rather than one per reference
  ;; (which would flood the DB pool).
  (let [existing
        (into #{}
              (map (juxt (comp keyword :type) :name :major))
              (dbs/q! db/ds ["SELECT DISTINCT type, name, major FROM AGORA_DOCUMENT"] dbs/kebab))
        docs (mapv (fn [row]
                     (merge
                      (update (select-keys row [:id :type :name :lang :major :minor]) :type keyword)
                      (dbs/decode-content (:content row))))
                   (dbs/q!
                    db/ds
                    ["SELECT id, type, name, lang, major, minor, content FROM AGORA_DOCUMENT"]
                    dbs/kebab))]
    (into (lineage/consistency-issues docs existing) (successor-cache-issues))))
