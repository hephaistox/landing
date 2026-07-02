(ns landing.agora.ki
  "Read access to Knowledge Items.

  A KI row stores only the hash of its output statement; this namespace resolves
  that hash to the actual text via the blob store, returning a single clean map."
  (:require
   [landing.agora.blob :as blob]
   [landing.agora.db :as db]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]))

(defn fetch-ki
  "Fetch the KI identified by `id`, resolving its output statement text from the
  blob store. Returns a map of the KI fields (unqualified, kebab-case) with an
  extra :output-statement key holding the resolved text, or nil if no such KI."
  [id]
  (when-let [row (jdbc/execute-one!
                  db/ds
                  ["SELECT id, name, type, major, minor, output_statement_hash, owner_id, published_at
                    FROM AGORA_KI WHERE id = ?" id]
                  {:builder-fn rs/as-unqualified-kebab-maps})]
    (assoc row :output-statement (blob/read-blob (:output-statement-hash row)))))
