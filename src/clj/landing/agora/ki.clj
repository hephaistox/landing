(ns landing.agora.ki
  "Read access to Knowledge Items.

  A KI row stores only the hash of its output statement; this namespace resolves
  that hash to the actual text via the blob store, returning a single clean map."
  (:require
   [landing.agora.blob :as blob]
   [landing.agora.db :as db]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs])
  (:import
   (java.time LocalDateTime ZoneOffset)))

(defn- ->utc-iso
  "Render a stored (UTC-convention) DATETIME as an ISO-8601 UTC string, e.g.
  \"2026-07-02T00:00:00Z\". Also makes the value JSON-serializable."
  [^LocalDateTime ldt]
  (some-> ldt (.atOffset ZoneOffset/UTC) .toInstant .toString))

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
    ki-type ki-name ki-major]
   {:builder-fn rs/as-unqualified-kebab-maps}))

(defn- edge-refs
  "The Major-only KI references (type, name, major) on the `wanted` side of edges
  whose `known` side is the given KI. `direction` :inputs looks at edges whose
  output is this KI (wanting their input); :successors is the mirror."
  [direction ki-type ki-name ki-major]
  (let [[known wanted] (case direction
                         :inputs     ["output" "input"]
                         :successors ["input" "output"])
        ;; `known`/`wanted` are fixed literals ("input"/"output"), never user input.
        sql (str "SELECT " wanted "_type AS type, " wanted "_name AS name, " wanted "_major AS major "
                 "FROM AGORA_KI_EDGE "
                 "WHERE " known "_type = ? AND " known "_name = ? AND " known "_major = ? "
                 "ORDER BY " wanted "_type, " wanted "_name, " wanted "_major")]
    (jdbc/execute! db/ds
                   [sql ki-type ki-name ki-major]
                   {:builder-fn rs/as-unqualified-kebab-maps})))

(defn- neighbours
  "Light KI refs reachable from the given KI across one edge, each Major-only
  reference resolved to its latest-minor row via `resolve-major`. `direction` is
  :inputs (KIs that imply this one) or :successors (KIs this one implies)."
  [direction ki-type ki-name ki-major]
  (->> (edge-refs direction ki-type ki-name ki-major)
       (keep (fn [{ref-type :type ref-name :name ref-major :major}]
               (resolve-major ref-type ref-name ref-major)))
       vec))

(defn fetch-ki
  "Fetch the KI identified by `id`, resolving its output statement text from the
  blob store and its graph neighbours. Returns a map of the KI fields
  (unqualified, kebab-case) plus:
   - :output-statement — the resolved text
   - :inputs           — light refs of the KIs that imply this one
   - :successors       — light refs of the KIs this one implies
  or nil if no such KI. :published-at is an ISO-8601 UTC string."
  [id]
  (when-let [row (jdbc/execute-one!
                  db/ds
                  ["SELECT id, name, type, major, minor, output_statement_hash, owner_id, published_at
                    FROM AGORA_KI WHERE id = ?" id]
                  {:builder-fn rs/as-unqualified-kebab-maps})]
    (let [{ki-type :type ki-name :name ki-major :major} row]
      (-> row
          (assoc :output-statement (blob/read-blob (:output-statement-hash row)))
          (update :published-at ->utc-iso)
          (assoc :inputs (neighbours :inputs ki-type ki-name ki-major))
          (assoc :successors (neighbours :successors ki-type ki-name ki-major))))))
