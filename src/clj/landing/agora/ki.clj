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
  row {:id :name :type :major :minor}, or nil when no KI with that major exists.
  Auto-resolution to the newest minor means a link follows clarifications of its
  target — including a type reclassification — without being rewritten."
  [ki-name ki-major]
  (jdbc/execute-one!
   db/ds
   ["SELECT id, name, type, major, minor FROM AGORA_KI
     WHERE object_type = 'ki' AND name = ? AND major = ? ORDER BY minor DESC LIMIT 1"
    ki-name
    ki-major]
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
  reference resolved to its latest-minor row via `resolve-major`. `direction` is
  :inputs (KIs that imply this one) or :successors (KIs this one implies)."
  [direction ki-name ki-major]
  (->> (edge-refs direction ki-name ki-major)
       (keep (fn [{ref-name :name
                   ref-major :major}]
               (resolve-major ref-name ref-major)))
       vec))

(defn- versions
  "All minors of the (name, major) lineage as [{:id :minor} …] ascending, so the
  UI can offer previous/next-version navigation."
  [ki-name ki-major]
  (jdbc/execute!
   db/ds
   ["SELECT id, minor FROM AGORA_KI WHERE object_type = 'ki' AND name = ? AND major = ? ORDER BY minor"
    ki-name
    ki-major]
   {:builder-fn rs/as-unqualified-kebab-maps}))

(defn fetch-ki
  "Fetch the KI identified by `id`, resolving its output statement text from the
  blob store and its graph neighbours. Returns a map of the KI fields
  (unqualified, kebab-case) plus:
   - :output-statement — the resolved text
   - :inputs           — light refs of the KIs that imply this one
   - :successors       — light refs of the KIs this one implies
   - :versions         — [{:id :minor} …] of this lineage's minors, ascending
  or nil if no such KI. :published-at is an ISO-8601 UTC string."
  [id]
  (when-let
    [row
     (jdbc/execute-one!
      db/ds
      ["SELECT id, name, type, major, minor, output_statement_hash, owner_id, published_at
                    FROM AGORA_KI WHERE id = ?"
       id]
      {:builder-fn rs/as-unqualified-kebab-maps})]
    (let [{ki-name :name
           ki-major :major}
          row]
      (-> row
          (assoc :output-statement (blob/read-blob (:output-statement-hash row)))
          (update :published-at util/->utc-iso)
          (assoc :inputs (neighbours :inputs ki-name ki-major))
          (assoc :successors (neighbours :successors ki-name ki-major))
          (assoc :versions (versions ki-name ki-major))))))

(defn fetch-ki-by-major
  "Fetch the latest-minor KI of the (name, major) lineage — the permanent public
  identity behind /ki/{name}/{major} — or nil if no such lineage exists."
  [ki-name ki-major]
  (when-let [{:keys [id]} (resolve-major ki-name ki-major)] (fetch-ki id)))

(defn- next-minor
  "The next minor number for the (`ki-name`, `ki-major`) lineage: one past the
  current highest minor, or 0 if none exists yet."
  [ki-name ki-major]
  (:m
   (jdbc/execute-one!
    db/ds
    ["SELECT COALESCE(MAX(minor) + 1, 0) AS m FROM AGORA_KI
         WHERE object_type = 'ki' AND name = ? AND major = ?"
     ki-name
     ki-major]
    {:builder-fn rs/as-unqualified-kebab-maps})))

(defn edit-ki
  "Edit the KI identified by `id`, producing a NEW minor version — never an
  in-place mutation. The new version keeps the source's name and major, takes the
  given `type` and output `statement`, and gets the next minor in its (name, major)
  lineage. The epistemic `type` is a plain attribute, so reclassifying is just an
  edit: the new minor auto-resolves for every KI referencing this major
  (resolve-major picks the new latest minor). Returns the new KI (via fetch-ki),
  or nil if `id` is unknown."
  [id {ki-type :type
       statement :output-statement}]
  (when-let [{ki-name :name
              ki-major :major}
             (jdbc/execute-one! db/ds
                                ["SELECT name, major FROM AGORA_KI WHERE id = ?" id]
                                {:builder-fn rs/as-unqualified-kebab-maps})]
    (let [hash (blob/write-blob statement)
          minor (next-minor ki-name ki-major)
          new-id (str (UUID/randomUUID))]
      (jdbc/execute!
       db/ds
       ["INSERT INTO AGORA_KI
         (id, object_type, name, type, major, minor, output_statement_hash, owner_id, published_at)
         VALUES (?, 'ki', ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP())"
        new-id
        ki-name
        ki-type
        ki-major
        minor
        hash
        nil])
      (fetch-ki new-id))))

(defn search-kis
  "KIs matching `q` (case-insensitive) in either their name OR their latest
  output-statement text, one light ref per lineage (latest minor). Blank `q`
  returns []. The statement text lives in the blob store, joined by hash. Used by
  the search UI (#37) and to pick an existing KI as an input (#33)."
  [q]
  (if (str/blank? q)
    []
    (let [like (str "%" q "%")]
      (jdbc/execute!
       db/ds
       ["SELECT k.id, k.name, k.type, k.major, k.minor FROM AGORA_KI k
         LEFT JOIN AGORA_BLOB b ON b.hash = k.output_statement_hash
         WHERE k.object_type = 'ki'
           AND (k.name LIKE ? OR b.content LIKE ?)
           AND k.minor = (SELECT MAX(k2.minor) FROM AGORA_KI k2
                          WHERE k2.object_type = 'ki' AND k2.name = k.name AND k2.major = k.major)
         ORDER BY k.name, k.major
         LIMIT 50"
        like
        like]
       {:builder-fn rs/as-unqualified-kebab-maps}))))

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
  "Up to 10 KIs, one light ref per lineage (latest minor, plus :visits), sampled
  at random weighted toward the most-visited (#36). The weight is (visits + 1) so
  never-visited KIs can still appear; ordering uses the Efraimidis–Spirakis
  weighted key POW(RAND(), 1/weight), so higher visits ⇒ higher chance of being
  in the top 10."
  []
  (jdbc/execute!
   db/ds
   ["SELECT k.id, k.name, k.type, k.major, k.minor, COALESCE(v.visits, 0) AS visits
     FROM AGORA_KI k
     LEFT JOIN AGORA_KI_VISIT v ON v.name = k.name AND v.major = k.major
     WHERE k.object_type = 'ki'
       AND k.minor = (SELECT MAX(k2.minor) FROM AGORA_KI k2
                      WHERE k2.object_type = 'ki' AND k2.name = k.name AND k2.major = k.major)
     ORDER BY POW(RAND(), 1.0 / (COALESCE(v.visits, 0) + 1)) DESC
     LIMIT 10"]
   {:builder-fn rs/as-unqualified-kebab-maps}))

(defn create-ki
  "Create a brand-new KI (major 1, minor 0) from `name`, `type` and output
  `statement`, and return it via fetch-ki. Used to create an input inflight while
  managing links (#33), and by the creation form (#34)."
  [{ki-name :name
    ki-type :type
    statement :output-statement}]
  (let [hash (blob/write-blob statement)
        id (str (UUID/randomUUID))]
    (jdbc/execute!
     db/ds
     ["INSERT INTO AGORA_KI
       (id, object_type, name, type, major, minor, output_statement_hash, owner_id, published_at)
       VALUES (?, 'ki', ?, ?, 1, 0, ?, ?, UTC_TIMESTAMP())"
      id
      ki-name
      ki-type
      hash
      nil])
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
