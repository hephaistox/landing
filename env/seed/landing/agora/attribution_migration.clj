(ns landing.agora.attribution-migration
  "One-shot pass moving the two person pointers out of every version's immutable `content` and into
  the `owner_id` / `author_id` columns.

  An identifier that gathers exactly one person's documents is personal data, however anonymous the
  name displayed beside it: it was served publicly for as long as the account lived, so anyone who
  read the corpus holds the mapping. Erasing a person therefore has to move it, and something inside
  an immutable blob cannot move. Attribution has a lifecycle the claim does not — rectified on a
  rename, reassigned on an erasure or a transfer — so it belongs in columns, where one indexed
  `UPDATE` reassigns it.

  Rewrites the existing blobs rather than only filling the columns: a document whose `content` still
  carried the old id would keep the very identifier the change exists to make reassignable.

  Order matters and the SQL steps around this one enforce it (see `docs`): the columns must exist and
  be nullable while this runs, and only afterwards can `owner_id` be made NOT NULL and the foreign
  keys added — a key added first would reject the rows this is about to fill.

  Lives in the `env/seed` environment. Dev points at the shared MySQL, so run it from a REPL:
    clj -M:env-dev:env-seed
    (require 'landing.agora.attribution-migration)
    (landing.agora.attribution-migration/migrate!)"
  (:require
   [clojure.edn          :as edn]
   [landing.agora.db     :as db]
   [next.jdbc            :as jdbc]
   [next.jdbc.result-set :as rs]))

(def ^:private kebab {:builder-fn rs/as-unqualified-kebab-maps})

(defn- rows
  "Every version with its raw `content`, and whatever the columns already hold."
  []
  (jdbc/execute! db/ds ["SELECT id, content, owner_id, author_id FROM AGORA_DOCUMENT"] kebab))

(defn- move
  "The `UPDATE` arguments for one row — `[content owner-id author-id id]` — or nil when its content
  carries neither pointer, so a re-run touches nothing."
  [{:keys [id content owner-id author-id]}]
  (let [c (or (some-> content
                      edn/read-string)
              {})]
    (when (or (:owner-id c) (:author-id c))
      [(pr-str (dissoc c :owner-id :author-id))
       (or (:owner-id c) owner-id)
       (or (:author-id c) author-id)
       id])))

(defn migrate!
  "Fill `owner_id` / `author_id` from each version's `content` and strip them from it. Returns the
  number of versions rewritten. Idempotent — a version whose content no longer holds either pointer
  is left alone."
  []
  (let [fixes (keep move (rows))]
    (doseq [args fixes]
      (jdbc/execute!
       db/ds
       (into ["UPDATE AGORA_DOCUMENT SET content = ?, owner_id = ?, author_id = ? WHERE id = ?"]
             args)))
    (count fixes)))

(defn unattributed
  "Versions left with no owner — what has to be empty before `owner_id` can be made NOT NULL. A
  non-empty result means a version's content never carried an owner, which needs a decision rather
  than a default."
  []
  (jdbc/execute! db/ds ["SELECT id, type, name FROM AGORA_DOCUMENT WHERE owner_id IS NULL"] kebab))

(defn dangling
  "Owner or credited-author ids pointing at no AGORA_USER row — what has to be empty before the
  foreign keys can be added."
  []
  (jdbc/execute!
   db/ds
   ["SELECT d.id, d.owner_id, d.author_id FROM AGORA_DOCUMENT d
      WHERE (d.owner_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM AGORA_USER u WHERE u.id = d.owner_id))
         OR (d.author_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM AGORA_USER u WHERE u.id = d.author_id))"]
   kebab))
