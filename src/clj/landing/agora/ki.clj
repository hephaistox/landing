(ns landing.agora.ki
  "Read access to Knowledge Items.

  A KI row stores only the hash of its output statement; this namespace resolves
  that hash to the actual text via the blob store, returning a single clean map."
  (:require
   [landing.agora.blob   :as blob]
   [landing.agora.db     :as db]
   [landing.agora.util   :as util]
   [next.jdbc            :as jdbc]
   [next.jdbc.result-set :as rs])
  (:import (java.util UUID)))

(defn resolve-major
  "Versioning-in-links utility (#30). Connections store a KI reference at Major
  granularity only (type, name, major); this resolves such a reference to its
  concrete latest-minor row {:id :name :type :major :minor}, or nil when no KI
  with that major exists. Auto-resolution to the newest minor means a link
  follows clarifications of its target without being rewritten."
  [ki-type ki-name ki-major]
  (jdbc/execute-one!
   db/ds
   ["SELECT id, name, type, major, minor FROM AGORA_KI
     WHERE type = ? AND name = ? AND major = ? ORDER BY minor DESC LIMIT 1"
    ki-type
    ki-name
    ki-major]
   {:builder-fn rs/as-unqualified-kebab-maps}))

(defn- edge-refs
  "The Major-only KI references (type, name, major) on the `wanted` side of edges
  whose `known` side is the given KI. `direction` :inputs looks at edges whose
  output is this KI (wanting their input); :successors is the mirror."
  [direction ki-type ki-name ki-major]
  (let [[known wanted] (case direction
                         :inputs ["output" "input"]
                         :successors ["input" "output"])
        ;; `known`/`wanted` are fixed literals ("input"/"output"), never user input.
        sql (str "SELECT "
                 wanted
                 "_type AS type, "
                 wanted
                 "_name AS name, "
                 wanted
                 "_major AS major "
                 "FROM AGORA_KI_EDGE "
                 "WHERE "
                 known
                 "_type = ? AND "
                 known
                 "_name = ? AND "
                 known
                 "_major = ? "
                 "ORDER BY "
                 wanted
                 "_type, "
                 wanted
                 "_name, "
                 wanted
                 "_major")]
    (jdbc/execute! db/ds
                   [sql ki-type ki-name ki-major]
                   {:builder-fn rs/as-unqualified-kebab-maps})))

(defn- neighbours
  "Light KI refs reachable from the given KI across one edge, each Major-only
  reference resolved to its latest-minor row via `resolve-major`. `direction` is
  :inputs (KIs that imply this one) or :successors (KIs this one implies)."
  [direction ki-type ki-name ki-major]
  (->> (edge-refs direction ki-type ki-name ki-major)
       (keep (fn [{ref-type :type
                   ref-name :name
                   ref-major :major}]
               (resolve-major ref-type ref-name ref-major)))
       vec))

(defn- versions
  "All minors of the (type, name, major) lineage as [{:id :minor} …] ascending,
  so the UI can offer previous/next-version navigation."
  [ki-type ki-name ki-major]
  (jdbc/execute!
   db/ds
   ["SELECT id, minor FROM AGORA_KI WHERE type = ? AND name = ? AND major = ? ORDER BY minor"
    ki-type
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
    (let [{ki-type :type
           ki-name :name
           ki-major :major}
          row]
      (-> row
          (assoc :output-statement (blob/read-blob (:output-statement-hash row)))
          (update :published-at util/->utc-iso)
          (assoc :inputs (neighbours :inputs ki-type ki-name ki-major))
          (assoc :successors (neighbours :successors ki-type ki-name ki-major))
          (assoc :versions (versions ki-type ki-name ki-major))))))

(defn- next-minor
  "The next minor number for the (`ki-type`, `ki-name`, `ki-major`) lineage:
  one past the current highest minor, or 0 if none exists yet."
  [ki-type ki-name ki-major]
  (:m
   (jdbc/execute-one!
    db/ds
    ["SELECT COALESCE(MAX(minor) + 1, 0) AS m FROM AGORA_KI
         WHERE type = ? AND name = ? AND major = ?"
     ki-type
     ki-name
     ki-major]
    {:builder-fn rs/as-unqualified-kebab-maps})))

(defn edit-ki
  "Edit the KI identified by `id`, producing a NEW minor version — never an
  in-place mutation. The new version keeps the source's name and major, takes the
  given `type` and output `statement` text, and gets the next minor in its
  (type, name, major) lineage. Because edges reference Major only, a same-type
  edit auto-resolves for every KI referencing this major (resolve-major picks the
  new latest minor). Returns the new KI (via fetch-ki), or nil if `id` is unknown.

  Note: type is part of a KI's identity, so changing it starts a distinct
  (type, name, major) lineage; edges (which reference type) intentionally do not
  follow a type change."
  [id {ki-type :type
       statement :output-statement}]
  (when-let [{ki-name :name
              ki-major :major}
             (jdbc/execute-one! db/ds
                                ["SELECT name, major FROM AGORA_KI WHERE id = ?" id]
                                {:builder-fn rs/as-unqualified-kebab-maps})]
    (let [hash (blob/write-blob statement)
          minor (next-minor ki-type ki-name ki-major)
          new-id (str (UUID/randomUUID))]
      (jdbc/execute!
       db/ds
       ["INSERT INTO AGORA_KI
         (id, name, type, major, minor, output_statement_hash, owner_id, published_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP())"
        new-id
        ki-name
        ki-type
        ki-major
        minor
        hash
        nil])
      (fetch-ki new-id))))
