(ns landing.agora.db.document
  "Raw AGORA_DOCUMENT SQL, plus the connection and EDN codec the queries share. No cache.
  No domain rules.

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

;; --- connection + failure handling. A DB error throws ::db-unavailable.

(defn- db-error!
  [e]
  (core-log/error-exception e "Agora DB error")
  (throw (ex-info "database unavailable" {:type ::db-unavailable} e)))

(defn- q! [& args] (try (apply jdbc/execute! args) (catch SQLException e (db-error! e))))
(defn- q1! [& args] (try (apply jdbc/execute-one! args) (catch SQLException e (db-error! e))))

(def ^:private kebab {:builder-fn rs/as-unqualified-kebab-maps})

(defn- t->s
  "Identity keyword (`:ki`, `:fr`) → its DB string. nil-safe."
  [t]
  (some-> t
          name))

;; --- EDN (de)serialization of the immutable `content` / mutable `computed` blobs

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

(defn computed
  "The **derived** blob of a version, stored in the mutable `computed` column: its resolved `:pins`
  and the byline `:author` (the display name of the person the version is attributed to, omitted
  when they have none). Both are caches of what the immutable `content` implies — a person renamed
  or erased is repaired here, never in `content`. One builder, so the write path and the reconcile
  produce the very same blob."
  [pins author]
  (cond-> {:pins (vec pins)}
    author (assoc :author author)))

(defn- truthy?
  "MySQL TINYINT(1) comes back as a Boolean or a 0/1 number. Normalize to a boolean."
  [v]
  (if (number? v) (not (zero? v)) (boolean v)))

;; --- id → full document(s) -------------------------------------------------------------------

(def ^:private select-doc
  "SELECT id, type, name, lang, major, minor, draft, publication_id, content, computed
     FROM AGORA_DOCUMENT ")

(defn- row->doc
  "A raw row → a full document: identity columns (`:type` and `:lang` keyworded), decoded `content`
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
  [conn {:keys [id type name lang major minor draft content computed published-at publication-id]}]
  (jdbc/execute-one!
   conn
   ["INSERT INTO AGORA_DOCUMENT
       (id, type, name, lang, major, minor, draft, content, computed, published_at, publication_id)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
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
    publication-id])
  id)

(defn insert!
  "Insert one document row (any type) — the generic write primitive for a version whose identity is
  already fixed (e.g. a create with a fresh cid at major 1, or a new draft of an existing lineage —
  `minor` NULL while draft). For a fork (a new major), use `insert-next-major!`, which picks the
  version number atomically. Returns the row `id`."
  [row]
  (try (insert-on! db/ds row) (catch SQLException e (db-error! e))))

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

;; --- document construction: shared identity/timestamp + a new-version row ---------------------

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
  `insert-next-minor!` / `insert-next-major!` / `publish-publication!`, which assign the version
  number atomically. Any document is versioned the same way, so this is shared across types."
  [doc {:keys [content computed draft publication-id published-at]}]
  {:id (str (UUID/randomUUID))
   :type (:type doc)
   :name (:name doc)
   :lang (:lang doc)
   :major (:major doc)
   :draft draft
   :content content
   :computed computed
   :published-at published-at
   :publication-id publication-id})

(defn update-draft!
  "Overwrite a draft's mutable blobs in place — a draft is edited in place (same id, no new version),
  not re-versioned. Sets `content`/`computed`/`published-at` on the row `id`, only while it is a
  `draft` (a published version is immutable). Returns the `id`."
  [{:keys [id content computed published-at]}]
  (q1!
   db/ds
   ["UPDATE AGORA_DOCUMENT SET content = ?, computed = ?, published_at = ?
           WHERE id = ? AND draft = 1"
    (pr-str content)
    (pr-str computed)
    published-at
    id])
  id)

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

(defn in-publication
  "The documents publication `pub-cid` gathers, newest first, as full documents. Each lineage has one
  row per publication (its draft, edited in place; published in place on close), so this is a flat
  select — no minor-collapse needed."
  [pub-cid]
  (mapv row->doc
        (q! db/ds
            [(str select-doc "WHERE publication_id = ? ORDER BY published_at DESC") pub-cid]
            kebab)))

(defn delete-draft!
  "Remove a lineage's draft versions that publication `pub-cid` gathers — (`type`, `name`, `lang`)
  with `draft = 1` and this `publication_id`. Published versions of the lineage stay. Returns the
  number of rows removed."
  [type name lang pub-cid]
  (:next.jdbc/update-count
   (q1!
    db/ds
    ["DELETE FROM AGORA_DOCUMENT
            WHERE type = ? AND name = ? AND lang = ? AND publication_id = ? AND draft = 1"
     (t->s type)
     name
     (t->s lang)
     pub-cid])))

(defn delete-publication!
  "Remove publication `pub-cid` and the drafts it gathers, in one transaction: the drafts
  (`draft = 1`, this `publication_id`) then every minor of the publication lineage. Published
  documents keep their `publication_id` as provenance."
  [pub-cid]
  (try
    (jdbc/with-transaction
     [tx db/ds]
     (jdbc/execute! tx
                    ["DELETE FROM AGORA_DOCUMENT WHERE publication_id = ? AND draft = 1" pub-cid])
     (jdbc/execute! tx
                    ["DELETE FROM AGORA_DOCUMENT WHERE type = 'publication' AND name = ?" pub-cid]))
    (catch SQLException e (db-error! e))))

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

(defn publish-publication!
  "Publish publication `pub-cid` in one transaction: give each draft it gathers its lineage's next
  published minor (`MAX(minor where draft=0)+1`), flip it to published **in place** — same `id`, so
  references pinned by id survive — keeping `publication_id` as permanent provenance, and rebuild its
  successor edges (it is now the latest published minor); then close the publication itself by
  inserting `close-row` as its next minor (status `:closed`). Each lineage is locked (`FOR UPDATE`)
  while its minor is computed, so concurrent writes can't collide. Returns the closed row's `id`."
  [pub-cid
   {:keys [type name lang major]
    :as close-row}]
  (try
    (jdbc/with-transaction
     [tx db/ds]
     (doseq
       [d
        (jdbc/execute!
         tx
         ["SELECT id, type, name, lang, major, content FROM AGORA_DOCUMENT
                                  WHERE publication_id = ? AND draft = 1"
          pub-cid]
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
         (jdbc/execute-one!
          tx
          ["UPDATE AGORA_DOCUMENT SET minor = ?, draft = 0 WHERE id = ?" (inc (or m -1)) (:id d)])
         (rebuild-edges-on! tx
                            (:type d)
                            (:name d)
                            (:lang d)
                            (:major d)
                            (:id d)
                            (:inputs (decode-content (:content d))))))
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
       (insert-on! tx (assoc close-row :minor (inc (or m -1))))))
    (catch SQLException e (db-error! e))))

(defn translations-of
  "The language siblings of the concept `name` (translation-by-name): the latest published minor in
  each (language, major), as `{:lang :name :major :title}`, ordered by language. A single-language
  concept yields one entry. Feeds the language switcher."
  [name]
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
                       WHERE name = ? AND draft = 0
                       GROUP BY lang, major) g
                  ON d.lang = g.lang AND d.major = g.major AND d.minor = g.latest
               WHERE d.name = ? AND d.draft = 0
               ORDER BY d.lang"
     name
     name]
    kebab)))

(defn fetch-latest-any
  "The latest minor of lineage (`type`, `name`), **drafts included**, or nil. For objects that live
  as drafts (e.g. a publication, open the whole time), where `latest-published-id` would find
  nothing."
  [type name]
  (some-> (q1! db/ds
               [(str select-doc "WHERE type = ? AND name = ? ORDER BY minor DESC LIMIT 1")
                (t->s type)
                name]
               kebab)
          row->doc))

(defn latest-any-of-type
  "The latest minor of every lineage of `type`, **drafts included**, all languages, newest first, as
  full documents. For draft-lived objects (publications); the caller filters (owner, status)."
  [type]
  (mapv
   row->doc
   (q!
    db/ds
    ["SELECT d.id, d.type, d.name, d.lang, d.major, d.minor, d.draft, d.publication_id,
                     d.content, d.computed
                FROM AGORA_DOCUMENT d
                JOIN (SELECT type, name, lang, major, MAX(minor) AS latest
                        FROM AGORA_DOCUMENT
                       WHERE type = ?
                       GROUP BY type, name, lang, major) g
                  ON d.type = g.type AND d.name = g.name AND d.lang = g.lang
                     AND d.major = g.major AND d.minor = g.latest
               ORDER BY d.published_at DESC"
     (t->s type)]
    kebab)))

(defn published-of-type
  "One page of the browse feed: the latest published minor of every lineage of `type`, newest first
  (`published_at`), as full documents. When `lang` is given the feed is that content language; a nil
  `lang` returns every language (the client then filters). `limit`/`offset` page it."
  [type lang limit offset]
  (mapv
   row->doc
   (q!
    db/ds
    (into
     [(str
       "SELECT d.id, d.type, d.name, d.lang, d.major, d.minor, d.draft, d.publication_id,
                     d.content, d.computed
                FROM AGORA_DOCUMENT d
                JOIN (SELECT type, name, lang, major, MAX(minor) AS latest
                        FROM AGORA_DOCUMENT
                       WHERE type = ? "
       (when lang "AND lang = ? ")
       "AND draft = 0
                       GROUP BY type, name, lang, major) g
                  ON d.type = g.type AND d.name = g.name AND d.lang = g.lang
                     AND d.major = g.major AND d.minor = g.latest
               WHERE d.draft = 0
               ORDER BY d.published_at DESC
               LIMIT ? OFFSET ?")]
     (concat [(t->s type)] (when lang [(t->s lang)]) [limit offset]))
    kebab)))

(defn versions-of-id
  "Every version of the lineage that contains `id`, drafts included, oldest→newest, as
  `{:id :minor :major :draft}` — for the admin version picker (fetched lazily). One query: the
  self-join pins the lineage (type, name, lang, major) from `id`, then lists its minors."
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

(defn search-of-type
  "The latest published minor of every lineage of `type` whose name or content matches `q` (substring,
  case-insensitive), as full documents, capped at `limit`. When `lang` is given the search is that
  content language; a nil `lang` searches every language. `content` is the EDN blob, so this matches
  title and body text; a blank `q` is the caller's concern."
  [type lang q limit]
  (let [like (str "%" (db/escape-like q) "%")]
    (mapv
     row->doc
     (q!
      db/ds
      (into
       [(str
         "SELECT d.id, d.type, d.name, d.lang, d.major, d.minor, d.draft, d.publication_id,
                     d.content, d.computed
                FROM AGORA_DOCUMENT d
                JOIN (SELECT type, name, lang, major, MAX(minor) AS latest
                        FROM AGORA_DOCUMENT
                       WHERE type = ? "
         (when lang "AND lang = ? ")
         "AND draft = 0
                       GROUP BY type, name, lang, major) g
                  ON d.type = g.type AND d.name = g.name AND d.lang = g.lang
                     AND d.major = g.major AND d.minor = g.latest
               WHERE d.draft = 0 AND (d.name LIKE ? OR d.content LIKE ?)
               ORDER BY d.name, d.major
               LIMIT ?")]
       (concat [(t->s type)] (when lang [(t->s lang)]) [like like limit]))
      kebab))))

(defn- published-latest-rows
  "Raw rows for the latest published minor of every lineage, all types and languages, newest first:
  identity columns + `published_at` + the `content` blob (decoded by the caller)."
  []
  (q!
   db/ds
   ["SELECT d.type, d.name, d.lang, d.major, d.published_at, d.content
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

(defn published-latest-docs
  "Every published lineage's latest minor, all types and languages, as decoded maps — the identity
  columns (`:type`/`:lang` kept as DB strings, the form URLs are built from), plus `:title`, `:kind`,
  `:author-id`, `:owner-id` and `:lastmod`. Newest first. One scan feeding both the sitemap and the
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
             :author-id (:author-id c)
             :owner-id (:owner-id c)
             :lastmod (:published-at row)}))
        (published-latest-rows)))

;; --- lineage indexes ------------------------------------------------------------------------

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
          :content (decode-content (:content row))
          :computed (decode-computed (:computed row))})
       (jdbc/execute!
        tx
        ["SELECT id, type, name, lang, major, minor, draft, publication_id, content, computed
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
