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
  (:import (java.sql SQLException)))

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
(defn- decode-pins
  [s]
  (:pins (or (some-> s
                     edn/read-string)
             {:pins {}})))

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
  (its `:kind` keyworded), resolved `:pins`."
  [row]
  (let [content (decode-content (:content row))]
    (cond-> (merge (-> (select-keys row [:id :type :name :lang :major :minor])
                       (update :type keyword)
                       (update :lang keyword))
                   {:draft (truthy? (:draft row))
                    :publication-id (:publication-id row)}
                   content
                   {:pins (decode-pins (:computed row))})
      (:kind content) (update :kind keyword))))

(defn fetch-id
  "Document for `id`, or nil."
  [id]
  (some-> (q1! db/ds [(str select-doc "WHERE id = ?") id] kebab)
          row->doc))

(defn insert!
  "Insert one document row (any type). `content`/`computed` are maps, EDN-encoded here; `draft` a
  boolean; `published-at` an ISO string; `publication-id` the change that created it, or nil. The
  generic write primitive — a leaf create (no inputs) is just this call; the input/pin/successor
  machinery is layered on top for citing documents. Returns the row `id`."
  [{:keys [id type name lang major minor draft content computed published-at publication-id]}]
  (q1!
   db/ds
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
  "One page of the browse feed: the latest published minor of every lineage of `type` in `lang`,
  newest first (`published_at`), as full documents. `limit`/`offset` page it."
  [type lang limit offset]
  (mapv
   row->doc
   (q!
    db/ds
    ["SELECT d.id, d.type, d.name, d.lang, d.major, d.minor, d.draft, d.publication_id,
                     d.content, d.computed
                FROM AGORA_DOCUMENT d
                JOIN (SELECT type, name, lang, major, MAX(minor) AS latest
                        FROM AGORA_DOCUMENT
                       WHERE type = ? AND lang = ? AND draft = 0
                       GROUP BY type, name, lang, major) g
                  ON d.type = g.type AND d.name = g.name AND d.lang = g.lang
                     AND d.major = g.major AND d.minor = g.latest
               WHERE d.draft = 0
               ORDER BY d.published_at DESC
               LIMIT ? OFFSET ?"
     (t->s type)
     (t->s lang)
     limit
     offset]
    kebab)))

(defn search-of-type
  "The latest published minor of every lineage of `type` in `lang` whose name or content matches `q`
  (substring, case-insensitive), as full documents, capped at `limit`. `content` is the EDN blob, so
  this matches title and body text; a blank `q` is the caller's concern."
  [type lang q limit]
  (let [like (str "%" q "%")]
    (mapv
     row->doc
     (q!
      db/ds
      ["SELECT d.id, d.type, d.name, d.lang, d.major, d.minor, d.draft, d.publication_id,
                     d.content, d.computed
                FROM AGORA_DOCUMENT d
                JOIN (SELECT type, name, lang, major, MAX(minor) AS latest
                        FROM AGORA_DOCUMENT
                       WHERE type = ? AND lang = ? AND draft = 0
                       GROUP BY type, name, lang, major) g
                  ON d.type = g.type AND d.name = g.name AND d.lang = g.lang
                     AND d.major = g.major AND d.minor = g.latest
               WHERE d.draft = 0 AND (d.name LIKE ? OR d.content LIKE ?)
               ORDER BY d.name, d.major
               LIMIT ?"
       (t->s type)
       (t->s lang)
       like
       like
       limit]
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
  the raw `:source` ref, `:owner-id` and `:lastmod`. Newest first. One scan feeding both the sitemap
  and the author hubs; each caller projects or filters (attribution is derived in the domain, so the
  raw `:source`/`:owner-id` ride along here rather than being resolved in SQL)."
  []
  (mapv (fn [row]
          (let [c (decode-content (:content row))]
            {:type (:type row)
             :name (:name row)
             :lang (:lang row)
             :major (:major row)
             :title (:title c)
             :kind (:kind c)
             :source (:source c)
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

;; --- reconcile: snapshot + targeted repair of the derived caches -----------------------------
;; The derived caches (`computed.:pins`, `AGORA_SUCCESSOR`) are reconciled against the immutable
;; `content` by *diffing*, not rewriting: read a consistent snapshot, let the caller compute the
;; discrepancies (see `admin/discrepancies`), then apply only those. A healthy corpus writes nothing.

(defn derivation-snapshot
  "A consistent point-in-time snapshot for the reconcile: every version's derivation data and every
  successor edge. Both reads run in one **repeatable-read** transaction, so they share a single read
  view — a write landing between the two SELECTs is invisible, and the versions and edges always
  agree (relying on the connection's default isolation would not guarantee this). Returns
  `{:versions [{:id :type :name :lang :major :minor :draft :inputs :computed}…]
    :edges #{[input-type input-name input-lang input-major successor-id]…}}`
  — `:type`/`:lang` keyworded, `:inputs`/`:computed` decoded; edges as DB-string tuples."
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
         (let [content (decode-content (:content row))]
           {:id (:id row)
            :type (keyword (:type row))
            :name (:name row)
            :lang (keyword (:lang row))
            :major (:major row)
            :minor (:minor row)
            :draft (truthy? (:draft row))
            :inputs (:inputs content)
            :computed (or (some-> (:computed row)
                                  edn/read-string)
                          {})}))
       (jdbc/execute!
        tx
        ["SELECT id, type, name, lang, major, minor, draft, content, computed
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

(defn apply-pin-fixes!
  "Overwrite `computed` for each `{:id :computed}` fix (the versions whose pins drifted). Batched."
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
