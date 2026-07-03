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

(defn- neighbours
  "Light KI refs (id, name, type, major, minor) reachable from the KI identified
  by (`ki-type`, `ki-name`, `ki-major`) across one edge.

  `direction` is :inputs (KIs that imply this one) or :successors (KIs this one
  implies). Edges store Major only, so each reference is resolved to its concrete
  latest-minor row — Slice 3 (#30) factors that resolution into a named utility."
  [direction ki-type ki-name ki-major]
  (let [[known wanted] (case direction
                         :inputs     ["output" "input"]
                         :successors ["input" "output"])
        sql (str "SELECT k.id, k.name, k.type, k.major, k.minor "
                 "FROM AGORA_KI_EDGE e "
                 "JOIN AGORA_KI k ON k.type = e." wanted "_type "
                 "               AND k.name = e." wanted "_name "
                 "               AND k.major = e." wanted "_major "
                 "WHERE e." known "_type = ? AND e." known "_name = ? AND e." known "_major = ? "
                 "AND k.minor = (SELECT MAX(k2.minor) FROM AGORA_KI k2 "
                 "               WHERE k2.type = k.type AND k2.name = k.name AND k2.major = k.major) "
                 "ORDER BY k.type, k.name, k.major")]
    ;; `known`/`wanted` are fixed literals ("input"/"output"), never user input.
    (jdbc/execute! db/ds
                   [sql ki-type ki-name ki-major]
                   {:builder-fn rs/as-unqualified-kebab-maps})))

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
