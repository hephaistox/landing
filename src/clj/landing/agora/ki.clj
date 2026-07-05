(ns landing.agora.ki
  "Read access to Knowledge Items.

  A KI row stores only the hash of its output statement; this namespace resolves
  that hash to the actual text via the blob store, returning a single clean map."
  (:require
   [clojure.string       :as str]
   [landing.agora.blob   :as blob]
   [landing.agora.db     :as db]
   [landing.agora.util   :as util]
   [next.jdbc            :as jdbc]
   [next.jdbc.result-set :as rs])
  (:import (java.util UUID)))

(defn resolve-major
  "Versioning-in-links utility (#30). Connections store a KI reference at Major
  granularity only (name, major — identity's T is the object type `ki`, not the
  epistemic type); this resolves such a reference to its concrete latest-minor
  row {:id :name :type :lang :major :minor}, or nil when no KI with that major
  exists. Auto-resolution to the newest minor means a link follows clarifications
  of its target — including a type reclassification — without being rewritten.

  Language-aware: edges are language-neutral (referenced by name only), so a
  reference resolves to the `lang` version of the concept when it exists, else
  falls back to any other language — so a French graph that is only partly
  translated still renders, showing untranslated nodes in their own language."
  [ki-name ki-major lang]
  (jdbc/execute-one!
   db/ds
   ["SELECT id, name, title, type, lang, major, minor FROM AGORA_KI
     WHERE object_type = 'ki' AND name = ? AND major = ?
     ORDER BY (lang = ?) DESC, minor DESC LIMIT 1"
    ki-name
    ki-major
    lang]
   {:builder-fn rs/as-unqualified-kebab-maps}))

(defn- edge-refs
  "The Major-only KI references (name, major) on the `wanted` side of edges whose
  `known` side is the given KI. `direction` :inputs looks at edges whose output is
  this KI (wanting their input); :successors is the mirror."
  [direction ki-name ki-major]
  (let [[known wanted] (case direction
                         :inputs ["output" "input"]
                         :successors ["input" "output"])
        ;; `known`/`wanted` are fixed literals ("input"/"output"), never user input.
        sql (str "SELECT "
                 wanted
                 "_name AS name, "
                 wanted
                 "_major AS major "
                 "FROM AGORA_KI_EDGE "
                 "WHERE "
                 known
                 "_name = ? AND "
                 known
                 "_major = ? "
                 "ORDER BY "
                 wanted
                 "_name, "
                 wanted
                 "_major")]
    (jdbc/execute! db/ds [sql ki-name ki-major] {:builder-fn rs/as-unqualified-kebab-maps})))

(defn- neighbours
  "Light KI refs reachable from the given KI across one edge, each Major-only
  reference resolved to its latest-minor row via `resolve-major`, preferring the
  `lang` translation of each neighbour. `direction` is :inputs (KIs that imply
  this one) or :successors (KIs this one implies)."
  [direction ki-name ki-major lang]
  (->> (edge-refs direction ki-name ki-major)
       (keep (fn [{ref-name :name
                   ref-major :major}]
               (resolve-major ref-name ref-major lang)))
       vec))

(defn- versions
  "All minors of the (name, major, lang) lineage as [{:id :minor} …] ascending, so
  the UI can offer previous/next-version navigation. Each language has its own
  minor lineage."
  [ki-name ki-major lang]
  (jdbc/execute!
   db/ds
   ["SELECT id, minor FROM AGORA_KI
     WHERE object_type = 'ki' AND name = ? AND major = ? AND lang = ? ORDER BY minor"
    ki-name
    ki-major
    lang]
   {:builder-fn rs/as-unqualified-kebab-maps}))

(defn- translations
  "Other-language versions of the same concept. Translations are grouped by
  identity Name (T,N): the sibling KIs share this `ki-name` but a different
  language. Returns one light ref {:name :major :lang} per other language. Powers
  the Wikipedia-style language switch on the KI page — no explicit link needed."
  [ki-name ki-lang]
  (jdbc/execute!
   db/ds
   ["SELECT DISTINCT name, major, lang FROM AGORA_KI
     WHERE object_type = 'ki' AND name = ? AND lang <> ?
     ORDER BY lang"
    ki-name
    ki-lang]
   {:builder-fn rs/as-unqualified-kebab-maps}))

(defn fetch-ki
  "Fetch the KI identified by `id`, resolving its output statement text from the
  blob store and its graph neighbours. Returns a map of the KI fields
  (unqualified, kebab-case) plus:
   - :output-statement — the resolved text
   - :author           — the owning user's display name (nil if unowned)
   - :inputs           — light refs of the KIs that imply this one
   - :successors       — light refs of the KIs this one implies
   - :versions         — [{:id :minor} …] of this lineage's minors, ascending
  or nil if no such KI. :published-at is an ISO-8601 UTC string."
  [id]
  (when-let
    [row
     (jdbc/execute-one!
      db/ds
      ["SELECT k.id, k.name, k.title, k.type, k.lang, k.major, k.minor, k.output_statement_hash,
              k.owner_id, k.published_at, u.display_name AS author
       FROM AGORA_KI k
       LEFT JOIN AGORA_USER u ON u.id = k.owner_id
       WHERE k.id = ?"
       id]
      {:builder-fn rs/as-unqualified-kebab-maps})]
    (let [{ki-name :name
           ki-major :major
           ki-lang :lang}
          row]
      (-> row
          (assoc :output-statement (blob/read-blob (:output-statement-hash row)))
          (update :published-at util/->utc-iso)
          (assoc :inputs (neighbours :inputs ki-name ki-major ki-lang))
          (assoc :successors (neighbours :successors ki-name ki-major ki-lang))
          (assoc :versions (versions ki-name ki-major ki-lang))
          (assoc :translations (translations ki-name ki-lang))))))

(defn fetch-ki-by-major
  "Fetch the latest-minor KI of the (name, major) lineage in language `lang` — the
  permanent public identity behind /agora/{lang}/ki/{name}/{major} — or nil if no
  such lineage exists. Falls back to another language when the concept is not yet
  translated into `lang` (see resolve-major)."
  [ki-name ki-major lang]
  (when-let [{:keys [id]} (resolve-major ki-name ki-major lang)] (fetch-ki id)))

(defn- next-minor
  "The next minor number for the (`ki-name`, `ki-major`, `lang`) lineage: one past
  the current highest minor, or 0 if none exists yet. Minors are per language."
  [ki-name ki-major lang]
  (:m
   (jdbc/execute-one!
    db/ds
    ["SELECT COALESCE(MAX(minor) + 1, 0) AS m FROM AGORA_KI
         WHERE object_type = 'ki' AND name = ? AND major = ? AND lang = ?"
     ki-name
     ki-major
     lang]
    {:builder-fn rs/as-unqualified-kebab-maps})))

(defn edit-ki
  "Edit the KI identified by `id`, producing a NEW minor version — never an
  in-place mutation. The new version keeps the source's name and major, takes the
  given `type` and output `statement`, and gets the next minor in its (name, major)
  lineage. The epistemic `type` is a plain attribute, so reclassifying is just an
  edit: the new minor auto-resolves for every KI referencing this major
  (resolve-major picks the new latest minor). `owner-id` is the editing user (the
  new minor's owner). Returns the new KI (via fetch-ki), or nil if `id` is unknown."
  [id
   owner-id
   {ki-type :type
    ki-title :title
    statement :output-statement}]
  (when-let [{ki-name :name
              ki-major :major
              ki-lang :lang}
             (jdbc/execute-one! db/ds
                                ["SELECT name, major, lang FROM AGORA_KI WHERE id = ?" id]
                                {:builder-fn rs/as-unqualified-kebab-maps})]
    (let [hash (blob/write-blob statement)
          minor (next-minor ki-name ki-major ki-lang)
          new-id (str (UUID/randomUUID))]
      (jdbc/execute!
       db/ds
       ;; the content language is immutable across minors — carry it forward (a
       ;; translation is a same-named KI in another language, not a new minor).
       ["INSERT INTO AGORA_KI
         (id, object_type, name, title, type, lang, major, minor, output_statement_hash, owner_id, published_at)
         VALUES (?, 'ki', ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP())"
        new-id
        ki-name
        ki-title
        ki-type
        ki-lang
        ki-major
        minor
        hash
        owner-id])
      (fetch-ki new-id))))

(defn- lang-exists?
  "True when a `lang` version of the (name, major) lineage already exists."
  [ki-name ki-major lang]
  (some?
   (jdbc/execute-one!
    db/ds
    ["SELECT id FROM AGORA_KI WHERE object_type = 'ki' AND name = ? AND major = ? AND lang = ? LIMIT 1"
     ki-name
     ki-major
     lang]
    {:builder-fn rs/as-unqualified-kebab-maps})))

(defn- create-in-lang!
  "Create a `to-lang` version (major/minor 0) of the (name, major) lineage with the
  given `title`, `statement` and `ki-type`, owned by `owner-id` — unless a
  `to-lang` version already exists."
  [ki-name ki-major ki-type ki-title to-lang statement owner-id]
  (when-not (lang-exists? ki-name ki-major to-lang)
    (jdbc/execute!
     db/ds
     ["INSERT INTO AGORA_KI
       (id, object_type, name, title, type, lang, major, minor, output_statement_hash, owner_id, published_at)
       VALUES (?, 'ki', ?, ?, ?, ?, ?, 0, ?, ?, UTC_TIMESTAMP())"
      (str (UUID/randomUUID))
      ki-name
      ki-title
      ki-type
      to-lang
      ki-major
      (blob/write-blob statement)
      owner-id])))

(defn- copy-lineage-to-lang!
  "Create a `to-lang` version (major/minor 0) of the (name, major) lineage by
  copying its latest `from-lang` statement, owned by `owner-id` — unless a
  `to-lang` version already exists. The blob is content-addressed, so the copy
  reuses the same hash (dedup) until the translator edits it. No-op when the
  source lineage has no `from-lang` row."
  [ki-name ki-major from-lang to-lang owner-id]
  (when-not (lang-exists? ki-name ki-major to-lang)
    (when-let
      [{ki-type :type
        ki-title :title
        hash :output-statement-hash}
       (jdbc/execute-one!
        db/ds
        ["SELECT type, title, output_statement_hash FROM AGORA_KI
                  WHERE object_type = 'ki' AND name = ? AND major = ? AND lang = ?
                  ORDER BY minor DESC LIMIT 1"
         ki-name
         ki-major
         from-lang]
        {:builder-fn rs/as-unqualified-kebab-maps})]
      (jdbc/execute!
       db/ds
       ["INSERT INTO AGORA_KI
         (id, object_type, name, title, type, lang, major, minor, output_statement_hash, owner_id, published_at)
         VALUES (?, 'ki', ?, ?, ?, ?, ?, 0, ?, ?, UTC_TIMESTAMP())"
        (str (UUID/randomUUID))
        ki-name
        ki-title
        ki-type
        to-lang
        ki-major
        hash
        owner-id]))))

(defn translate-ki
  "Create a `to-lang` version of the KI `id` and its direct inputs, owned by
  `owner-id`. The KI itself takes the author-validated `statement` (the edited
  translation, falling back to the source text if blank); its direct inputs are
  copied from their source text as placeholders the author can refine later.
  Existing versions are left untouched, and edges are shared by name so the graph
  comes along automatically. Returns the `to-lang` KI (via fetch-ki-by-major), or
  nil if `id` is unknown."
  [id to-lang owner-id title statement]
  (when-let [src (fetch-ki id)]
    (create-in-lang! (:name src)
                     (:major src)
                     (:type src)
                     (if (str/blank? title) (:title src) title)
                     to-lang
                     (if (str/blank? statement) (:output-statement src) statement)
                     owner-id)
    (doseq [{in-name :name
             in-major :major
             in-lang :lang}
            (:inputs src)]
      (copy-lineage-to-lang! in-name in-major in-lang to-lang owner-id))
    (fetch-ki-by-major (:name src) (:major src) to-lang)))

(defn search-kis
  "KIs matching `q` (case-insensitive) in either their name OR their latest
  output-statement text, one light ref per lineage (latest minor), restricted to
  the content language `lang`. Blank `q` returns []. The statement text lives in
  the blob store, joined by hash. Used by the search UI (#37) and to pick an
  existing KI as an input (#33)."
  [q lang]
  (if (str/blank? q)
    []
    (let [like (str "%" q "%")]
      (jdbc/execute!
       db/ds
       ["SELECT k.id, k.name, k.title, k.type, k.lang, k.major, k.minor FROM AGORA_KI k
         LEFT JOIN AGORA_BLOB b ON b.hash = k.output_statement_hash
         WHERE k.object_type = 'ki'
           AND k.lang = ?
           AND (k.name LIKE ? OR k.title LIKE ? OR b.content LIKE ?)
           AND k.minor = (SELECT MAX(k2.minor) FROM AGORA_KI k2
                          WHERE k2.object_type = 'ki' AND k2.name = k.name
                            AND k2.major = k.major AND k2.lang = k.lang)
         ORDER BY k.name, k.major
         LIMIT 50"
        lang
        like
        like
        like]
       {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn list-tnrs
  "All KI lineages (a TNR = object-type + name + major) with aggregate counts, for
  the admin page: version rows, distinct languages and the latest minor."
  []
  (jdbc/execute!
   db/ds
   ["SELECT name, major,
            COUNT(*) AS versions,
            COUNT(DISTINCT lang) AS langs,
            MAX(minor) AS latest
     FROM AGORA_KI WHERE object_type = 'ki'
     GROUP BY name, major
     ORDER BY name, major"]
   {:builder-fn rs/as-unqualified-kebab-maps}))

(defn delete-tnr!
  "Drop an entire (name, major) lineage — every language and minor — plus its edges
  and visit counter. Content blobs are content-addressed and may be shared, so they
  are left in place. Returns the number of KI rows deleted."
  [ki-name ki-major]
  (jdbc/execute!
   db/ds
   ["DELETE FROM AGORA_KI_EDGE
     WHERE (input_name = ? AND input_major = ?) OR (output_name = ? AND output_major = ?)"
    ki-name
    ki-major
    ki-name
    ki-major])
  (jdbc/execute! db/ds ["DELETE FROM AGORA_KI_VISIT WHERE name = ? AND major = ?" ki-name ki-major])
  (:next.jdbc/update-count
   (jdbc/execute-one!
    db/ds
    ["DELETE FROM AGORA_KI WHERE object_type = 'ki' AND name = ? AND major = ?" ki-name ki-major])))

(defn compact-tnr!
  "Keep only the latest minor of each language of a (name, major) lineage, deleting
  the older minors. Edges reference (name, major) only, so they are untouched.
  Returns the number of KI rows deleted."
  [ki-name ki-major]
  (->>
    (jdbc/execute!
     db/ds
     ["SELECT lang, MAX(minor) AS latest FROM AGORA_KI
          WHERE object_type = 'ki' AND name = ? AND major = ? GROUP BY lang"
      ki-name
      ki-major]
     {:builder-fn rs/as-unqualified-kebab-maps})
    (reduce
     (fn [n {:keys [lang latest]}]
       (+
        n
        (or
         (:next.jdbc/update-count
          (jdbc/execute-one!
           db/ds
           ["DELETE FROM AGORA_KI
                            WHERE object_type = 'ki' AND name = ? AND major = ? AND lang = ? AND minor < ?"
            ki-name
            ki-major
            lang
            latest]))
         0)))
     0)))

(defn sitemap-rows
  "Every public KI permalink as {:name :major :lang :lastmod} — one row per
  language of each (name, major) lineage, with its latest publication date. Feeds
  the sitemap (#39)."
  []
  (jdbc/execute!
   db/ds
   ["SELECT name, major, lang, MAX(published_at) AS lastmod
     FROM AGORA_KI WHERE object_type = 'ki'
     GROUP BY name, major, lang
     ORDER BY name, major, lang"]
   {:builder-fn rs/as-unqualified-kebab-maps}))

(defn record-visit
  "Increment the public-page visit counter for the (name, major) lineage."
  [ki-name ki-major]
  (jdbc/execute!
   db/ds
   ["INSERT INTO AGORA_KI_VISIT (name, major, visits) VALUES (?, ?, 1)
     ON DUPLICATE KEY UPDATE visits = visits + 1"
    ki-name
    ki-major]))

(defn list-kis
  "Up to 10 KIs, one light ref per lineage (latest minor, plus :visits and the
  :output-statement text for a card preview), sampled
  at random weighted toward the most-visited (#36). The weight is (visits + 1) so
  never-visited KIs can still appear; ordering uses the Efraimidis–Spirakis
  weighted key POW(RAND(), 1/weight), so higher visits ⇒ higher chance of being
  in the top 10. Restricted to the content language `lang`."
  [lang]
  (jdbc/execute!
   db/ds
   ["SELECT k.id, k.name, k.title, k.type, k.lang, k.major, k.minor, COALESCE(v.visits, 0) AS visits,
            b.content AS output_statement
     FROM AGORA_KI k
     LEFT JOIN AGORA_KI_VISIT v ON v.name = k.name AND v.major = k.major
     LEFT JOIN AGORA_BLOB b ON b.hash = k.output_statement_hash
     WHERE k.object_type = 'ki'
       AND k.lang = ?
       AND k.minor = (SELECT MAX(k2.minor) FROM AGORA_KI k2
                      WHERE k2.object_type = 'ki' AND k2.name = k.name
                        AND k2.major = k.major AND k2.lang = k.lang)
     ORDER BY POW(RAND(), 1.0 / (COALESCE(v.visits, 0) + 1)) DESC
     LIMIT 10"
    lang]
   {:builder-fn rs/as-unqualified-kebab-maps}))

(defn create-ki
  "Create a brand-new KI (major 1, minor 0) owned by `owner-id`, from `name`,
  `type` and output `statement`; return it via fetch-ki. Used to create an input
  inflight while managing links (#33), and by the creation form (#34)."
  [owner-id {ki-name :name
             ki-title :title
             ki-type :type
             ki-lang :lang
             statement :output-statement}]
  (let [hash (blob/write-blob statement)
        id (str (UUID/randomUUID))]
    (jdbc/execute!
     db/ds
     ;; Translations need no linking: a KI created with an existing name in another
     ;; language is automatically a sibling (grouped by identity Name).
     ["INSERT INTO AGORA_KI
       (id, object_type, name, title, type, lang, major, minor, output_statement_hash, owner_id, published_at)
       VALUES (?, 'ki', ?, ?, ?, ?, 1, 0, ?, ?, UTC_TIMESTAMP())"
      id
      ki-name
      ki-title
      ki-type
      (or ki-lang "fr")
      hash
      owner-id])
    (fetch-ki id)))

(defn- ki-ref
  "The (name, major) identity of the KI `id`, or nil."
  [id]
  (jdbc/execute-one! db/ds
                     ["SELECT name, major FROM AGORA_KI WHERE id = ?" id]
                     {:builder-fn rs/as-unqualified-kebab-maps}))

(defn add-input
  "Add an input edge: the KI referenced by `input` (a {:name :major} Major ref)
  implies the KI identified by `id`. Edges reference (name, major) and are
  idempotent (INSERT IGNORE on the unique edge key). Returns the updated KI via
  fetch-ki, or nil if `id` is unknown."
  [id {in-name :name
       in-major :major}]
  (when-let [{out-name :name
              out-major :major}
             (ki-ref id)]
    (jdbc/execute!
     db/ds
     ["INSERT IGNORE INTO AGORA_KI_EDGE
       (id, input_name, input_major, output_name, output_major)
       VALUES (?, ?, ?, ?, ?)"
      (str (UUID/randomUUID))
      in-name
      in-major
      out-name
      out-major])
    (fetch-ki id)))

(defn drop-input
  "Remove the input edge from the KI referenced by `input` to the KI `id`.
  Returns the updated KI via fetch-ki, or nil if `id` is unknown."
  [id {in-name :name
       in-major :major}]
  (when-let [{out-name :name
              out-major :major}
             (ki-ref id)]
    (jdbc/execute!
     db/ds
     ["DELETE FROM AGORA_KI_EDGE
       WHERE input_name = ? AND input_major = ? AND output_name = ? AND output_major = ?"
      in-name
      in-major
      out-name
      out-major])
    (fetch-ki id)))
