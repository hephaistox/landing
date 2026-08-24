(ns landing.agora.db.document
  "Raw AGORA_DOCUMENT SQL, plus the connection and EDN codec the queries share. No cache.
  No domain rules.

  Queries here stay simple — a narrowing `WHERE` over one table, assembled and joined in Clojure by
  the layer above. Three are the deliberate exception, and say so where they are defined: the
  latest-minor collapse (see the block above `latest-page`).

  The `type` and `lang` columns are text (`\"ki\"`, `\"fr\"`); the domain uses keywords (`:ki`,
  `:fr`). `fetch` keywords them on read; queries coerce back with `t->s`. Other fields are returned
  as stored."
  (:require
   [auto-core.log        :as core-log]
   [clojure.edn          :as edn]
   [landing.agora.db     :as db]
   [next.jdbc            :as jdbc]
   [next.jdbc.result-set :as rs])
  (:import (java.sql SQLException)
           (java.time Instant)
           (java.time.temporal ChronoUnit)
           (java.util UUID)))

;; --------------------------------------------------------------------------------
;; --- connection + failure handling. A DB error throws :landing.agora.db-unavailable.
;; --------------------------------------------------------------------------------

(defn db-error!
  "Log a DB failure and rethrow it as `:landing.agora.db-unavailable`. Public, so a namespace that owns its own
  transaction converts failures the same way the queries here do."
  [e]
  (core-log/error-exception e "Agora DB error")
  (throw (ex-info "database unavailable" {:type :landing.agora.db-unavailable} e)))

(defn- q! [& args] (try (apply jdbc/execute! args) (catch SQLException e (db-error! e))))
(defn- q1! [& args] (try (apply jdbc/execute-one! args) (catch SQLException e (db-error! e))))

(def ^:private kebab {:builder-fn rs/as-unqualified-kebab-maps})

(defn- t->s
  "Identity keyword (`:ki`, `:fr`) → its DB string. nil-safe."
  [t]
  (some-> t
          name))

;; --------------------------------------------------------------------------------
;; --- EDN (de)serialization of the immutable `content` / mutable `computed` blobs
;; --------------------------------------------------------------------------------

(defn- decode-content
  [s]
  (or (some-> s
              edn/read-string)
      {}))
(defn- decode-computed
  [s]
  (or (some-> s
              edn/read-string)
      {}))

(defn build-computed
  "Build the **derived** blob of a version, for the mutable `computed` column: its resolved `:pins` and
  the byline `:author` (the display name of the person the version is attributed to, omitted when they
  have none). Both are caches of what the immutable `content` implies — a person renamed or erased is
  repaired here, never in `content`.

  Called from two places and never on read: the write path, to store the blob, and the reconcile, to
  say what it *should* be. One builder for both, since the reconcile compares the blob **whole** — two
  constructions differing by so much as a vector where the other builds a list would have it rewrite
  the corpus on every pass, or never repair anything."
  [pins author]
  (cond-> {:pins (vec pins)}
    author (assoc :author author)))

(defn- truthy?
  "MySQL TINYINT(1) comes back as a Boolean or a 0/1 number. Normalize to a boolean."
  [v]
  (if (number? v) (not (zero? v)) (boolean v)))

;; --------------------------------------------------------------------------------
;; --- id → full document(s) -------------------------------------------------------------------
;; --------------------------------------------------------------------------------

(def select-doc
  "The column list every document read shares, with `row->doc` as its decoder. Public so a namespace
  that owns a document *concept* — `document.publication` — can write its own query in its own
  vocabulary without restating the shape."
  "SELECT id, type, name, lang, major, minor, draft, publication_id, owner_id, author_id,
          content, computed
     FROM AGORA_DOCUMENT ")

(defn row->doc
  "A raw row (from `select-doc`) → a full document: identity columns (`:type` and `:lang` keyworded), decoded `content`
  (its `:kind` keyworded), and the derived `:pins`/`:author` from `computed` — which is read last, so
  the byline a document displays is always the derived one."
  [row]
  (let [content (decode-content (:content row))
        computed (decode-computed (:computed row))]
    (cond-> (merge (-> (select-keys row [:id :type :name :lang :major :minor])
                       (update :type keyword)
                       (update :lang keyword))
                   {:draft (truthy? (:draft row))
                    :publication-id (:publication-id row)}
                   content
                   ;; the two person pointers are columns, merged after `content` so they are the
                   ;; ones a document carries even where an old blob still holds a copy
                   (select-keys row [:owner-id :author-id :author])
                   {:pins (:pins computed [])
                    :author (:author computed)})
      (:kind content) (update :kind keyword))))

(defn fetch-id
  "Document for `id`, or nil."
  [id]
  (some-> (q1! db/ds [(str select-doc "WHERE id = ?") id] kebab)
          row->doc))

(defn- insert-on!
  "Insert one document row on `conn` (a datasource or an open transaction). `content`/`computed` are
  maps, EDN-encoded here; `draft` a boolean. Returns the row `id`."
  [conn
   {:keys [id
           type
           name
           lang
           major
           minor
           draft
           content
           computed
           published-at
           publication-id
           owner-id
           author-id]}]
  (jdbc/execute-one!
   conn
   ["INSERT INTO AGORA_DOCUMENT
       (id, type, name, lang, major, minor, draft, content, computed, published_at, publication_id,
        owner_id, author_id)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    id
    (t->s type)
    name
    (t->s lang)
    major
    minor
    (if draft 1 0)
    (pr-str content)
    (pr-str computed)
    published-at
    publication-id
    owner-id
    author-id])
  id)

(defn insert!
  "Insert one document row (any type) — the generic write primitive for a version whose identity is
  already fixed (e.g. a create with a fresh cid at major 1, or a new draft of an existing lineage —
  `minor` NULL while draft). For a fork (a new major), use `insert-next-major!`, which picks the
  version number atomically. Returns the row `id`.

  Takes a connection when the caller owns the transaction — it then decides what to do with a
  failure, having more than one statement to undo."
  ([row] (try (insert-on! db/ds row) (catch SQLException e (db-error! e))))
  ([conn row] (insert-on! conn row)))

(defn insert-next-minor!
  "In one transaction: lock the lineage (`type`, `name`, `lang`, `major`), compute `minor = MAX+1`,
  and insert `row` with it. The `SELECT … FOR UPDATE` serializes concurrent minor-creation on the
  same lineage, so two simultaneous edits can't assign the same minor (no lost-update race). Returns
  the row `id`."
  [{:keys [type name lang major]
    :as row}]
  (try
    (jdbc/with-transaction
     [tx db/ds]
     (let
       [m
        (:m
         (jdbc/execute-one!
          tx
          ["SELECT MAX(minor) AS m FROM AGORA_DOCUMENT
                       WHERE type = ? AND name = ? AND lang = ? AND major = ? FOR UPDATE"
           (t->s type)
           name
           (t->s lang)
           major]
          kebab))]
       (insert-on! tx (assoc row :minor (inc (or m -1))))))
    (catch SQLException e (db-error! e))))

(defn upsert-draft!
  "Put `row` in its publication as **the** draft of its lineage: overwrite the one already there, or
  insert it. Returns the draft's `id`, stable across repeated edits.

  In one transaction, the lineage locked (`SELECT … FOR UPDATE`) while it is decided — the same
  serialization `insert-next-minor!` uses, and for the same reason. Deciding outside a transaction
  leaves a window in which two concurrent edits of one lineage both find nothing and both insert, and
  nothing below catches it: a draft's `minor` is NULL, and `uq_ki_identity` holds no two NULLs to be
  equal, so the unique index does not constrain drafts at all.

  Only the mutable state is overwritten — content, derived blob, timestamp, and the credited author.
  The owner is not: editing never changes who a draft belongs to (an edit by anyone else forks
  instead), and reassigning it is `attribution/reattribute!`'s job."
  [{:keys [type name lang major publication-id content computed published-at author-id]
    :as row}]
  (try
    (jdbc/with-transaction
     [tx db/ds]
     (if-let
       [id
        (:id
         (jdbc/execute-one!
          tx
          ["SELECT id FROM AGORA_DOCUMENT
                            WHERE type = ? AND name = ? AND lang = ? AND major = ?
                              AND publication_id = ? AND draft = 1
                            LIMIT 1 FOR UPDATE"
           (t->s type)
           name
           (t->s lang)
           major
           publication-id]
          kebab))]
       (do
         (jdbc/execute!
          tx
          ["UPDATE AGORA_DOCUMENT SET content = ?, computed = ?, published_at = ?, author_id = ?
                    WHERE id = ? AND draft = 1"
           (pr-str content)
           (pr-str computed)
           published-at
           author-id
           id])
         id)
       (insert-on! tx row)))
    (catch SQLException e (db-error! e))))

(defn insert-next-major!
  "In one transaction: lock the concept (`type`, `name`, `lang`), compute `major = MAX+1`, and insert
  `row` at that major (a fork). `row` keeps its own `minor` — a fork is a **draft**, so `minor` is
  NULL until publish. The `SELECT … FOR UPDATE` serializes concurrent forks, so two simultaneous
  forks can't assign the same major. Returns the row `id`."
  [{:keys [type name lang]
    :as row}]
  (try
    (jdbc/with-transaction
     [tx db/ds]
     (let
       [m
        (:m
         (jdbc/execute-one!
          tx
          ["SELECT MAX(major) AS m FROM AGORA_DOCUMENT
                       WHERE type = ? AND name = ? AND lang = ? FOR UPDATE"
           (t->s type)
           name
           (t->s lang)]
          kebab))]
       (insert-on! tx (assoc row :major (inc (or m 0))))))
    (catch SQLException e (db-error! e))))

;; --------------------------------------------------------------------------------
;; --- document construction: shared identity/timestamp + a new-version row ---------------------
;; --------------------------------------------------------------------------------

(def ^:private cid-alphabet "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")

(defn gen-cid
  "A random 10-char base62 cid — the opaque, stable lineage name, never derived from the title so a
  rename never dangles a reference."
  []
  (apply str (repeatedly 10 #(rand-nth cid-alphabet))))

(defn now-iso
  "The current UTC instant as a sortable second-resolution ISO-8601 string (matches `published_at`)."
  []
  (str (.truncatedTo (Instant/now) ChronoUnit/SECONDS)))

(defn version-row
  "A row for a **new version** of `doc`'s lineage — same (type, name, lang, major), a fresh id, the
  given `content`, `computed`, `draft` and `publication-id`, stamped `published-at`. Feed to
  `insert-next-minor!` / `insert-next-major!` / `publish-drafts!`, which assign the version
  number atomically. Any document is versioned the same way, so this is shared across types."
  [doc
   {:keys [content computed draft publication-id published-at owner-id author-id]
    :or {owner-id (:owner-id doc)
         author-id (:author-id doc)}}]
  {:id (str (UUID/randomUUID))
   :type (:type doc)
   :name (:name doc)
   :lang (:lang doc)
   :major (:major doc)
   :draft draft
   :content content
   :computed computed
   :published-at published-at
   :publication-id publication-id
   :owner-id owner-id
   :author-id author-id})

(defn draft-of
  "The `id` of the draft that publication `pub-cid` holds for lineage (`type`, `name`, `lang`,
  `major`), or nil — a lineage has at most one draft per publication (edited in place)."
  [type name lang major pub-cid]
  (:id
   (q1!
    db/ds
    ["SELECT id FROM AGORA_DOCUMENT
            WHERE type = ? AND name = ? AND lang = ? AND major = ? AND publication_id = ? AND draft = 1
            LIMIT 1"
     (t->s type)
     name
     (t->s lang)
     major
     pub-cid]
    kebab)))

(defn delete-draft!
  "Remove the draft publication `pub-cid` holds of lineage (`type`, `name`, `lang`, `major`).
  Published versions of the lineage stay. Returns the number of rows removed.

  Keyed on the **major** too, since that is what a lineage is: forking someone else's document lands
  a new major in the forker's own publication, so one publication can hold drafts of several majors
  of the same concept. Without it, deleting one would take the others with it — and the caller holds
  a single id, so it would remove more than it was asked to."
  [type name lang major pub-cid]
  (:next.jdbc/update-count
   (q1!
    db/ds
    ["DELETE FROM AGORA_DOCUMENT
            WHERE type = ? AND name = ? AND lang = ? AND major = ? AND publication_id = ?
              AND draft = 1"
     (t->s type)
     name
     (t->s lang)
     major
     pub-cid])))

(defn- rebuild-edges-on!
  "On `tx`, make the now-published doc `sid` the sole successor-edge holder for its lineage (`type`,
  `name`, `lang`, `major` — DB strings): delete every edge whose successor is any version of that
  lineage, then insert one edge per declared input TNLR in `inputs` (input-TNLR → `sid`). So
  AGORA_SUCCESSOR holds exactly the latest published minor's edges."
  [tx type name lang major sid inputs]
  (jdbc/execute!
   tx
   ["DELETE s FROM AGORA_SUCCESSOR s JOIN AGORA_DOCUMENT v ON v.id = s.successor_id
                     WHERE v.type = ? AND v.name = ? AND v.lang = ? AND v.major = ?"
    type
    name
    lang
    major])
  (when (seq inputs)
    (jdbc/execute-batch!
     tx
     "INSERT IGNORE INTO AGORA_SUCCESSOR
        (input_type, input_name, input_lang, input_major, successor_id) VALUES (?, ?, ?, ?, ?)"
     (mapv (fn [inp] [(t->s (:type inp)) (:name inp) (t->s (:lang inp)) (:major inp) sid]) inputs)
     {})))

(defn publish-drafts!
  "On the transaction `tx`, publish every draft publication `cid` gathers: each takes its lineage's
  next published minor (`MAX(minor where draft=0)+1`) and flips **in place** — same `id`, so
  references pinned by id survive — keeping `publication_id` as permanent provenance, and its
  successor edges are rebuilt, it being now the latest published minor. Each lineage is locked while
  its minor is computed, so concurrent writes can't collide.

  Does exactly that and no more: closing the publication is the caller's, who owns the transaction
  the two share. Assigning minors and rebuilding edges is versioning, on the same private machinery as
  `insert-next-minor!`, which is why it lives here rather than with the publication concept."
  [tx cid]
  (doseq
    [d
     (jdbc/execute!
      tx
      ["SELECT id, type, name, lang, major, content FROM AGORA_DOCUMENT
                               WHERE publication_id = ? AND draft = 1"
       cid]
      kebab)]
    (let
      [m
       (:m
        (jdbc/execute-one!
         tx
         ["SELECT MAX(minor) AS m FROM AGORA_DOCUMENT
                      WHERE type = ? AND name = ? AND lang = ? AND major = ? AND draft = 0
                      FOR UPDATE"
          (:type d)
          (:name d)
          (:lang d)
          (:major d)]
         kebab))]
      (jdbc/execute!
       tx
       ["UPDATE AGORA_DOCUMENT SET minor = ?, draft = 0 WHERE id = ?" (inc (or m -1)) (:id d)])
      (rebuild-edges-on! tx
                         (:type d)
                         (:name d)
                         (:lang d)
                         (:major d)
                         (:id d)
                         (:inputs (decode-content (:content d)))))))

(defn translations-of
  "The language siblings of the concept `(type, name)` (translation-by-name): the latest published
  minor in each (language, major), as `{:lang :name :major :title}`, ordered by language. A
  single-language concept yields one entry. Feeds the language switcher.

  Keyed on the **type** as well as the name, because that is what identity is — (Type, Name, Lang,
  Major, Minor). On the name alone, two documents of different types sharing one would have their
  minors collapsed together: the highest wins the (lang, major) group, and the other disappears from
  its own language switcher. Names are random cids, so nothing collides today; the query should not
  be correct only by that accident. Filtering both halves on the type is what keeps the join from
  crossing — the subquery needs no `type` column of its own, since every row it groups already has
  the same one."
  [type name]
  (mapv
   (fn [row]
     {:lang (:lang row)
      :name (:name row)
      :major (:major row)
      :title (:title (decode-content (:content row)))})
   (q!
    db/ds
    ["SELECT d.lang, d.name, d.major, d.content
                FROM AGORA_DOCUMENT d
                JOIN (SELECT lang, major, MAX(minor) AS latest
                        FROM AGORA_DOCUMENT
                       WHERE type = ? AND name = ? AND draft = 0
                       GROUP BY lang, major) g
                  ON d.lang = g.lang AND d.major = g.major AND d.minor = g.latest
               WHERE d.type = ? AND d.name = ? AND d.draft = 0
               ORDER BY d.lang"
     (t->s type)
     name
     (t->s type)
     name]
    kebab)))

;; --------------------------------------------------------------------------------
;; --- the latest-minor collapse: the one place a JOIN is kept ---------------------------------
;; --------------------------------------------------------------------------------
;;
;; The two queries below (`latest-page`, `latest-published-docs`) both answer
;; the same shape — *the latest minor of every lineage* — with a self-JOIN on a grouped MAX. They are
;; the exception to keeping this namespace to simple narrowings and doing the assembling in Clojure.
;;
;; Why they keep it: the alternative is to read every version of every lineage and reduce in memory.
;; That is free at a hundred versions and quadratic nonsense at a hundred thousand — a paginated feed
;; would fetch the whole corpus to return twenty cards. Collapsing a group to its maximum is index
;; work, which is what a database is for; joining *entities* is assembly, which is what Clojure is
;; for. The line falls between those two, not at the word JOIN.
;;
;; What would remove the need rather than move it: a `latest` flag maintained on write, turning all
;; three into `WHERE latest = 1`. Simpler SQL, no JOIN — at the cost of one more derived value for
;; the reconcile to keep honest. Worth it only when the corpus makes these queries hurt.

(defn latest-page
  "The latest minor of every lineage, newest first, as full documents — narrowed by whichever of
  `:type`, `:lang`, `:q` and `:owner` is given, and paged by `:limit`/`:offset`. `:owner` matches
  either person pointer, which is a **superset** of the attribution rule — the caller narrows the rest
  with `kind/attributed-author-id`, so the rule stays in one place while the database does the work. A nil `:lang` spans every
  content language (the client then filters); `:q` matches a substring of the name or of the content,
  case-insensitively, which reaches title and body text alike.

  **Published only, unless `:drafts?`**, which drops the `draft = 0` filter. That reaches an object
  whose minors are *numbered* while unpublished — a publication, open its whole life, which a
  published-only collapse would never show.

  It does **not** reach a document draft, and cannot: a draft's `minor` is NULL until publish, and
  `MAX(minor)` ignores NULLs. A lineage with both keeps its published minor; a lineage with only a
  draft has `MAX(minor)` NULL and drops out of the join entirely. Listing document drafts is `draft-versions`,
  a flat select with no collapse — which it can be because a lineage has at most one draft per
  publication (`upsert-draft!`).

  One query rather than one per narrowing: browsing, searching and listing draft-lived objects differ
  by a `LIKE` and a flag. Apart, they would write the latest-minor collapse three times, and the join
  below is subtle enough that a fix to one copy would not reach the others."
  [{:keys [type lang q owner limit offset drafts?]}]
  (let [like (when q (str "%" (db/escape-like q) "%"))]
    (mapv
     row->doc
     (q!
      db/ds
      (into
       [(str
         "SELECT d.id, d.type, d.name, d.lang, d.major, d.minor, d.draft, d.publication_id,
                     d.owner_id, d.author_id, d.content, d.computed
                FROM AGORA_DOCUMENT d
                JOIN (SELECT type, name, lang, major, MAX(minor) AS latest
                        FROM AGORA_DOCUMENT
                       WHERE 1 = 1 "
         (when-not drafts? "AND draft = 0 ")
         (when type "AND type = ? ")
         (when lang "AND lang = ? ")
         "GROUP BY type, name, lang, major) g
                  ON d.type = g.type AND d.name = g.name AND d.lang = g.lang
                     AND d.major = g.major AND d.minor = g.latest
               WHERE 1 = 1 "
         (when-not drafts? "AND d.draft = 0 ")
         (when owner "AND (d.owner_id = ? OR d.author_id = ?) ")
         (when like "AND (d.name LIKE ? OR d.content LIKE ?) ")
         "ORDER BY d.published_at DESC"
         (when limit " LIMIT ?")
         (when offset " OFFSET ?"))]
       (concat (when type [(t->s type)])
               (when lang [(t->s lang)])
               (when owner [owner owner])
               (when like [like like])
               (when limit [limit])
               (when offset [offset])))
      kebab))))

(defn sibling-versions
  "Every version of the lineage that contains `id`, drafts included, oldest→newest, as
  `{:id :minor :major :draft}` — for the admin version picker (fetched lazily). One query: the
  self-join pins the lineage (type, name, lang, major) from `id`, then lists its minors.

  Goes from a **version to its siblings**, where `latest-published-id` goes the other way — from a
  lineage to the version it resolves to. The two are complementary and never interchangeable; naming
  this one `-of-id` said what it took and not what it gave, which read like the other's mirror."
  [id]
  (mapv
   (fn [row]
     {:id (:id row)
      :minor (:minor row)
      :major (:major row)
      :draft (truthy? (:draft row))})
   (q!
    db/ds
    ["SELECT v.id, v.minor, v.major, v.draft
                FROM AGORA_DOCUMENT v
                JOIN AGORA_DOCUMENT d ON d.id = ?
               WHERE v.type = d.type AND v.name = d.name AND v.lang = d.lang AND v.major = d.major
               ORDER BY v.minor"
     id]
    kebab)))

(defn- latest-published-rows
  "Raw rows for the latest published minor of every lineage, all types and languages, newest first:
  identity columns + the person pointers + `published_at` + the `content` blob (decoded by the
  caller)."
  []
  (q!
   db/ds
   ["SELECT d.type, d.name, d.lang, d.major, d.owner_id, d.author_id, d.published_at, d.content
                  FROM AGORA_DOCUMENT d
                  JOIN (SELECT type, name, lang, major, MAX(minor) AS latest
                          FROM AGORA_DOCUMENT
                         WHERE draft = 0
                         GROUP BY type, name, lang, major) g
                    ON d.type = g.type AND d.name = g.name AND d.lang = g.lang
                       AND d.major = g.major AND d.minor = g.latest
                 WHERE d.draft = 0
                 ORDER BY d.published_at DESC"]
   kebab))

(defn latest-published-docs
  "Every published lineage's latest minor, all types and languages, as decoded maps — the identity
  columns (`:type`/`:lang` kept as DB strings, the form URLs are built from), plus `:title` and
  `:kind` from the content, the `:author-id`/`:owner-id` columns, and `:lastmod`. Newest first. One scan feeding both the sitemap and the
  author hubs; each caller projects or filters (attribution is derived in the domain, so the raw
  `:author-id`/`:owner-id` ride along here rather than being resolved in SQL)."
  []
  (mapv (fn [row]
          (let [c (decode-content (:content row))]
            {:type (:type row)
             :name (:name row)
             :lang (:lang row)
             :major (:major row)
             :title (:title c)
             :kind (:kind c)
             :author-id (:author-id row)
             :owner-id (:owner-id row)
             :lastmod (:published-at row)}))
        (latest-published-rows)))

;; --------------------------------------------------------------------------------
;; --- lineage indexes ------------------------------------------------------------------------
;; --------------------------------------------------------------------------------

(defn latest-published-id
  "The `id` of the latest published minor of a lineage (a `ref`'s type, name, lang, major), or nil
  when it has no published minor in that language. A single indexed lookup — the caller caches the
  tnlr→id mapping. No cross-language fallback: a lineage absent in `lang` resolves to nil."
  [{:keys [type name lang major]}]
  (:id
   (q1!
    db/ds
    ["SELECT id FROM AGORA_DOCUMENT
                WHERE type = ? AND name = ? AND lang = ? AND major = ? AND draft = 0
                ORDER BY minor DESC LIMIT 1"
     (t->s type)
     name
     (t->s lang)
     major]
    kebab)))

(defn latest-of
  "The current id a TNLR resolves to, **publication-aware**: publication `pub-cid`'s own draft of the
  lineage if it has one (so authoring in a publication points at its in-progress version), else the
  latest published minor. nil when neither exists. Shared by pin computation (write) and pin-staleness
  detection (read)."
  [{:keys [type name lang major]
    :as tnlr}
   pub-cid]
  (or (draft-of type name lang major pub-cid) (latest-published-id tnlr)))

;; --- reconcile: snapshot + targeted repair of the derived caches -----------------------------
;; The derived caches (the whole `computed` blob, `AGORA_SUCCESSOR`) are reconciled against the immutable
;; `content` by *diffing*, not rewriting: read a consistent snapshot, let the caller compute the
;; discrepancies (see `admin/discrepancies`), then apply only those. A healthy corpus writes nothing.

(defn derivation-snapshot
  "A consistent point-in-time snapshot for the reconcile: every version's derivation data and every
  successor edge. Both reads run in one **repeatable-read** transaction, so they share a single read
  view — a write landing between the two SELECTs is invisible, and the versions and edges always
  agree (relying on the connection's default isolation would not guarantee this). Returns
  `{:versions [{:id :type :name :lang :major :minor :draft :content :computed}…]
    :edges #{[input-type input-name input-lang input-major successor-id]…}}`
  — `:type`/`:lang` keyworded, `:content`/`:computed` decoded; edges as DB-string tuples. The whole
  immutable `content` rides along: it is what the derived blob is diffed against."
  []
  (try
    (jdbc/with-transaction
     [tx
      db/ds
      {:read-only true
       :isolation :repeatable-read}]
     {:versions
      (mapv
       (fn [row]
         {:id (:id row)
          :type (keyword (:type row))
          :name (:name row)
          :lang (keyword (:lang row))
          :major (:major row)
          :minor (:minor row)
          :draft (truthy? (:draft row))
          :publication-id (:publication-id row)
          :owner-id (:owner-id row)
          :author-id (:author-id row)
          :content (decode-content (:content row))
          :computed (decode-computed (:computed row))})
       (jdbc/execute!
        tx
        ["SELECT id, type, name, lang, major, minor, draft, publication_id, owner_id, author_id,
                                   content, computed
                            FROM AGORA_DOCUMENT"]
        kebab))
      :edges
      (into
       #{}
       (map (fn [e]
              [(:input-type e) (:input-name e) (:input-lang e) (:input-major e) (:successor-id e)]))
       (jdbc/execute!
        tx
        ["SELECT input_type, input_name, input_lang, input_major, successor_id
                         FROM AGORA_SUCCESSOR"]
        kebab))})
    (catch SQLException e (db-error! e))))

(defn attribution-rows
  "Every version as `{:id :owner-id :author-id :content :computed}` — what a byline refresh reads: the
  columns name the person the version is attributed to, the derived blob holds the cached name to
  repair.
  Lighter than `derivation-snapshot`: no edges and no pins to re-resolve, since a rename moves
  nothing but the name."
  []
  (mapv (fn [row]
          {:id (:id row)
           :owner-id (:owner-id row)
           :author-id (:author-id row)
           :content (decode-content (:content row))
           :computed (decode-computed (:computed row))})
        (q! db/ds ["SELECT id, owner_id, author_id, content, computed FROM AGORA_DOCUMENT"] kebab)))

(defn reattribute!
  "Point every version owned by `from-id` — and every one credited to them — at `to-id`. One indexed
  `UPDATE` per pointer, not a corpus walk: that is what having the person in a column rather than
  inside the immutable blob buys. Returns `{:owned n :credited n}`; the caller re-derives the
  bylines, which are cached from the person the version now points at.

  Both directions of a person's presence move together, so nothing is left behind pointing at
  `from-id` — the foreign key would refuse to delete them otherwise, which is the guarantee."
  [from-id to-id]
  (try (jdbc/with-transaction
        [tx db/ds]
        {:owned (or (:next.jdbc/update-count
                     (jdbc/execute-one!
                      tx
                      ["UPDATE AGORA_DOCUMENT SET owner_id = ? WHERE owner_id = ?" to-id from-id]))
                    0)
         :credited
         (or (:next.jdbc/update-count
              (jdbc/execute-one!
               tx
               ["UPDATE AGORA_DOCUMENT SET author_id = ? WHERE author_id = ?" to-id from-id]))
             0)})
       (catch SQLException e (db-error! e))))

(defn draft-versions
  "Draft versions as full documents, newest first, narrowed by whichever of `:type`, `:lang`, `:owner`
  and `:limit` is given — every draft when nothing is. An `:owner` matches either pointer: what a
  person is credited with is theirs to see as much as what they own.

  A **flat** select, not the latest-minor collapse `latest-page` uses: a draft has no `minor` until
  publish, so `MAX(minor)` would ignore it and a draft-only lineage would vanish. Flat is enough
  because a lineage has at most one draft per publication (`upsert-draft!`).

  Narrowing only, no opinion about **who may ask**: this namespace holds raw SQL and no domain rules,
  and \"a draft is its author's and the platform owner's\" is a rule (`auth/sees-drafts-of?`). The
  caller decides and passes the filter down, so the restriction is still applied by the database and
  never to a wider result after the fact."
  [{:keys [type lang owner limit]}]
  (mapv row->doc
        (q! db/ds
            (into [(str select-doc
                        "WHERE draft = 1 "
                        (when type "AND type = ? ")
                        (when lang "AND lang = ? ")
                        (when owner "AND (owner_id = ? OR author_id = ?) ")
                        "ORDER BY published_at DESC"
                        (when limit " LIMIT ?"))]
                  (concat (when type [(t->s type)])
                          (when lang [(t->s lang)])
                          (when owner [owner owner])
                          (when limit [limit])))
            kebab)))

(defn recent-attributions
  "One row per distinct pair of person pointers, with the last time that pair signed anything:
  `[{:owner-id :author-id :last-at}…]`, most recent first.

  The collapse is the point. Asking \"who wrote here lately\" by reading versions newest-first and
  keeping distinct people means reading the corpus to answer with a handful of names — the cost grows
  with what has been written, not with what is asked. Grouped, the result is bounded by the number of
  distinct pairs, which is about the number of *people*, and it stays that size however large the
  corpus grows.

  Pairs rather than resolved people: which of the two pointers is the author is
  `kind/attributed-author-id`'s to say, and a `COALESCE` here would be that rule written a second
  time. The caller applies it and dedupes what collapses together."
  []
  (q!
   db/ds
   ["SELECT owner_id, author_id, MAX(published_at) AS last_at
       FROM AGORA_DOCUMENT
      GROUP BY owner_id, author_id
      ORDER BY last_at DESC"]
   kebab))

(defn attributed-count
  "How many versions point at `person-id`, as owner or as credited author. What tells a person who
  can be deleted from one the foreign key will refuse — asked before the delete so the answer is a
  count the caller can show, not an integrity violation to catch."
  [person-id]
  (or (:n (q1! db/ds
               ["SELECT COUNT(*) AS n FROM AGORA_DOCUMENT WHERE owner_id = ? OR author_id = ?"
                person-id
                person-id]
               kebab))
      0))

(defn apply-computed-fixes!
  "Overwrite `computed` for each `{:id :computed}` fix (the versions whose derived blob drifted).
  Batched."
  [fixes]
  (when (seq fixes)
    (try (with-open [conn (jdbc/get-connection db/ds)]
           (jdbc/execute-batch! conn
                                "UPDATE AGORA_DOCUMENT SET computed = ? WHERE id = ?"
                                (mapv (fn [{:keys [id computed]}] [(pr-str computed) id]) fixes)
                                {}))
         (catch SQLException e (db-error! e)))))

(defn insert-successor-edges!
  "Insert reverse-edge tuples `[input-type input-name input-lang input-major successor-id]` (missing
  edges). IGNORE dupes so a concurrent write that already added one is harmless. Batched."
  [tuples]
  (when (seq tuples)
    (try
      (with-open [conn (jdbc/get-connection db/ds)]
        (jdbc/execute-batch!
         conn
         "INSERT IGNORE INTO AGORA_SUCCESSOR
                                (input_type, input_name, input_lang, input_major, successor_id)
                              VALUES (?, ?, ?, ?, ?)"
         (mapv vec tuples)
         {}))
      (catch SQLException e (db-error! e)))))

(defn delete-successor-edges!
  "Delete reverse-edge tuples (stale edges) precisely by their five-column key — never a table wipe,
  so only the drifted rows are touched. Batched."
  [tuples]
  (when (seq tuples)
    (try
      (with-open [conn (jdbc/get-connection db/ds)]
        (jdbc/execute-batch!
         conn
         "DELETE FROM AGORA_SUCCESSOR
                               WHERE input_type = ? AND input_name = ? AND input_lang = ?
                                 AND input_major = ? AND successor_id = ?"
         (mapv vec tuples)
         {}))
      (catch SQLException e (db-error! e)))))

(defn successor-latest-ids
  "For a `ref` (an input lineage's type, name, lang, major), the id of each successor lineage's
  latest published minor that declares `ref` as an input. AGORA_SUCCESSOR holds only those latest
  minors (see `rebuild-derived!`), so this is a plain reverse lookup — one id per successor
  lineage, no join or collapse."
  [{:keys [type name lang major]}]
  (mapv
   :successor-id
   (q!
    db/ds
    ["SELECT successor_id FROM AGORA_SUCCESSOR
            WHERE input_type = ? AND input_name = ? AND input_lang = ? AND input_major = ?"
     (t->s type)
     name
     (t->s lang)
     major]
    kebab)))
