(ns landing.agora.source
  "Bibliographic sources — the shared work (`AGORA_SOURCE`): a *work* authored by a
  person (`AGORA_USER`, possibly login-less/external), with title, year, editor and a url.

  One source maps to **many `kind=source` KIs** — one per quotation/idea — which share the
  source's fields and each carry their own locator. A `kind=source` KI references its source on
  `content.:source = {:source-id :locator}`; `resolve-ref` turns that into the source's display
  fields + the locator, on read. A read-through Caffeine cache fronts id → resolved source, so
  resolving never N+1s the DB. No dependency on `document` — keeps the graph acyclic
  (document → source, never the reverse)."
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
  "SELECT s.id, s.person_id, s.title, s.year, s.editor, s.url, s.created_by, u.display_name AS author_name
     FROM AGORA_SOURCE s JOIN AGORA_USER u ON u.id = s.person_id ")

(defn- shape
  "DB row → resolved source {:id :title :year :editor :url :author-name :author-id :owner-id}.
  `:owner-id` is the account that created the record (`created_by`) — used to gate editing to
  the source's owner (distinct from `:author-id`, the work's author)."
  [row]
  (when row
    {:id (:id row)
     :title (:title row)
     :year (:year row)
     :editor (:editor row)
     :url (:url row)
     :author-name (:author-name row)
     :author-id (:person-id row)
     :owner-id (:created-by row)}))

(defn- load-source [id] (shape (q1 (str select-source "WHERE s.id = ?") id)))

(def ^:private source-cache
  "id → resolved source. Evicted on `update!`; a create warms nothing (the new id is fetched
  on first use)."
  (cache/loading 20000 load-source))

(defn by-id [id] (cache/fetch source-cache id))

(defn resolve-ref
  "Resolve a `kind=source` KI's `content.:source` ref `{:source-id :locator}` to the shared
  source's display fields + the per-KI locator: `{:id :title :year :editor :url :author-name
  :author-id :source-id :locator}`. nil for a blank/absent ref or an unknown source (graceful)."
  [{:keys [source-id locator]}]
  (when-not (str/blank? source-id)
    (some-> (by-id source-id)
            (assoc :source-id source-id :locator locator))))

(defn create!
  "Create a source authored by `person-id`, owned by `owner-id`; returns the resolved source."
  [owner-id {:keys [person-id title year editor url]}]
  (let [id (str (UUID/randomUUID))]
    (jdbc/execute!
     db/ds
     ["INSERT INTO AGORA_SOURCE (id, person_id, title, year, editor, url, created_by, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP())"
      id
      person-id
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
  to the account that created it** (`owner-id`). Evicts the cache so every citing KI
  re-resolves. Returns the resolved source on success, `:forbidden` when `owner-id` is not the
  owner, or `nil` when the source does not exist."
  [id owner-id {:keys [person-id title year editor url]}]
  (if-let [s (by-id id)]
    (if (= owner-id (:owner-id s))
      (do
        (jdbc/execute!
         db/ds
         ["UPDATE AGORA_SOURCE SET person_id = ?, title = ?, year = ?, editor = ?, url = ? WHERE id = ?"
          person-id
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
  "Sources matching any subset of {:author :title :year} (ANDed); all blank → []. Author matches
  the joined AGORA_USER display name. Resolved rows, LIMIT 30."
  [{:keys [author title year]}]
  (let [clauses (cond-> []
                  (not (str/blank? title)) (conj ["s.title LIKE ?" (str "%" (str/trim title) "%")])
                  (not (str/blank? author)) (conj ["u.display_name LIKE ?"
                                                   (str "%" (str/trim author) "%")])
                  (some? year) (conj ["s.year = ?" year]))]
    (if (empty? clauses)
      []
      (mapv shape
            (apply q
                   (str select-source
                        "WHERE "
                        (str/join " AND " (map first clauses))
                        " ORDER BY s.title LIMIT 30")
                   (mapcat rest clauses))))))

(defn list-recent
  "The most-recently-created sources by `owner-id` (one-click reuse), LIMIT 10."
  [owner-id]
  (mapv shape
        (q (str select-source "WHERE s.created_by = ? ORDER BY s.created_at DESC LIMIT 10")
           owner-id)))
