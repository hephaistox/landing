(ns landing.agora.source-old
  "Bibliographic sources — the shared work (`AGORA_SOURCE`): a *work* authored by a
  person (`AGORA_USER`, possibly login-less/external), with title, year, editor and a url.

  The author's display name is **denormalized into `author_name` at creation** (and re-derived on
  edit), so reads need no join. One source maps to **many `kind=source` KIs** — one per
  citation/idea — which share the source's fields and each carry their own locator. A `kind=source`
  KI references its source on `content.:source = {:source-id :locator}`; `resolve-ref` turns that
  into the source's display fields + the locator, on read. A read-through Caffeine cache fronts
  id → resolved source, so resolving never N+1s the DB. No dependency on `document` — keeps the graph
  acyclic (document → source, never the reverse)."
  (:require
   [clojure.string       :as str]
   [landing.agora.cache  :as cache]
   [landing.agora.db     :as db]
   [next.jdbc            :as jdbc]
   [next.jdbc.result-set :as rs])
  (:import (java.util UUID)))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})
(defn- q [sql & params] (jdbc/execute! db/ds (into [sql] params) opts))
(defn- q1 [sql & params] (jdbc/execute-one! db/ds (into [sql] params) opts))

(def ^:private select-source
  "SELECT source_id, author_id, author_name, title, year, editor, url, owner_id FROM AGORA_SOURCE ")

(defn- shape
  "DB row → resolved source `{:id :author-id :author-name :title :year :editor :url :owner-id}`. The
  work's id is served as `:id` (old wire contract; the domain calls it `source_id`), `:author-id` is
  the author, `:owner-id` the account that created the record."
  [row]
  (when row
    (assoc (select-keys row [:author-id :author-name :title :year :editor :url :owner-id])
           :id
           (:source-id row))))

(defn- person-display-name
  "Display name of person `id` (an AGORA_USER), denormalized into a source's `author_name` at write."
  [id]
  (:display-name (q1 "SELECT display_name FROM AGORA_USER WHERE id = ?" id)))

(defn- load-source [id] (shape (q1 (str select-source "WHERE source_id = ?") id)))

(def ^:private source-cache
  "id → resolved source. Evicted on `update!`; a create warms nothing (the new id is fetched
  on first use)."
  (cache/loading 20000 load-source))

(defn by-id [id] (cache/fetch source-cache id))

(defn resolve-ref
  "Resolve a `kind=source` KI's `content.:source` ref `{:source-id :locator}` to the shared
  source's display fields + the per-KI locator: `{:id :author-id :author-name :title :year :editor
  :url :source-id :locator}`. nil for a blank/absent ref or an unknown source (graceful)."
  [{:keys [source-id locator]}]
  (when-not (str/blank? source-id)
    (some-> (by-id source-id)
            (assoc :source-id source-id :locator locator))))

(defn create!
  "Create a source authored by `person-id`, owned by `owner-id`; the author's display name is
  denormalized into `author_name` at creation. Returns the resolved source."
  [owner-id {:keys [person-id title year editor url]}]
  (let [id (str (UUID/randomUUID))]
    (jdbc/execute!
     db/ds
     ["INSERT INTO AGORA_SOURCE
         (source_id, author_id, author_name, title, year, editor, url, owner_id, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP())"
      id
      person-id
      (person-display-name person-id)
      (str/trim (or title ""))
      year
      (some-> editor
              str/trim
              not-empty)
      (some-> url
              str/trim
              not-empty)
      owner-id])
    (by-id id)))

(defn update!
  "Edit an existing source's fields in place (sources are shared, not versioned), **restricted
  to the account that created it** (`owner-id`). Re-derives `author_name` from the author. Evicts the
  cache so every citing KI re-resolves. Returns the resolved source on success, `:forbidden` when
  `owner-id` is not the owner, or `nil` when the source does not exist."
  [id owner-id {:keys [person-id title year editor url]}]
  (if-let [s (by-id id)]
    (if (= owner-id (:owner-id s))
      (do
        (jdbc/execute!
         db/ds
         ["UPDATE AGORA_SOURCE
             SET author_id = ?, author_name = ?, title = ?, year = ?, editor = ?, url = ?
           WHERE source_id = ?"
          person-id
          (person-display-name person-id)
          (str/trim (or title ""))
          year
          (some-> editor
                  str/trim
                  not-empty)
          (some-> url
                  str/trim
                  not-empty)
          id])
        (cache/evict! source-cache id)
        (by-id id))
      :forbidden)
    nil))

(defn search
  "Sources matching any subset of {:author :title :year} (ANDed); all blank → []. Author matches the
  denormalized `author_name`. Resolved rows, LIMIT 30."
  [{:keys [author title year]}]
  (let [clauses (cond-> []
                  (not (str/blank? title)) (conj ["title LIKE ?" (str "%" (str/trim title) "%")])
                  (not (str/blank? author)) (conj ["author_name LIKE ?"
                                                   (str "%" (str/trim author) "%")])
                  (some? year) (conj ["year = ?" year]))]
    (if (empty? clauses)
      []
      (mapv shape
            (apply q
                   (str select-source
                        "WHERE "
                        (str/join " AND " (map first clauses))
                        " ORDER BY title LIMIT 30")
                   (mapcat rest clauses))))))

(defn list-recent
  "The most-recently-created sources by `owner-id` (one-click reuse), LIMIT 10."
  [owner-id]
  (mapv shape
        (q (str select-source "WHERE owner_id = ? ORDER BY created_at DESC LIMIT 10") owner-id)))
