(ns landing.agora.source
  "Reusable bibliographic sources (AGORA_SOURCE) — a *work* authored by a person
  (AGORA_USER), cited by documents via {source-id, locator}. A read-through Caffeine
  cache fronts id → resolved source (title/year/editor + author name/id), so resolving a
  document's references never N+1s the DB. No dependency on `document` — keeps the graph
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
  "SELECT s.id, s.person_id, s.title, s.year, s.editor, u.display_name AS author_name
     FROM AGORA_SOURCE s JOIN AGORA_USER u ON u.id = s.person_id ")

(defn- shape
  "DB row → resolved source {:id :title :year :editor :author-name :author-id}."
  [row]
  (when row
    {:id (:id row)
     :title (:title row)
     :year (:year row)
     :editor (:editor row)
     :author-name (:author-name row)
     :author-id (:person-id row)}))

(defn- load-source [id] (shape (q1 (str select-source "WHERE s.id = ?") id)))

(def ^:private source-cache
  "id → resolved source. nil (unknown id) is not cached; a create warms nothing (the new
  id is fetched on first use). Sources are immutable in this slice, so no eviction."
  (cache/loading 20000 load-source))

(defn by-id [id] (cache/fetch source-cache id))

(defn resolve-refs
  "A document's raw references [{:source-id :locator}…] → resolved [{…source :locator}…],
  dropping any whose source no longer exists."
  [refs]
  (into []
        (keep (fn [{:keys [source-id locator]}]
                (some-> (by-id source-id)
                        (assoc :locator locator))))
        (or refs [])))

(defn create!
  "Create a source authored by `person-id`, owned by `owner-id`; returns the resolved source."
  [owner-id {:keys [person-id title year editor]}]
  (let [id (str (UUID/randomUUID))]
    (jdbc/execute!
     db/ds
     ["INSERT INTO AGORA_SOURCE (id, person_id, title, year, editor, created_by, created_at)
       VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP())"
      id
      person-id
      (str/trim (or title ""))
      year
      (some-> editor
              str/trim
              not-empty)
      owner-id])
    (by-id id)))

(defn update!
  "Edit an existing source's fields in place (sources are shared, not versioned), evict
  the cache so every citing document re-resolves, and return the resolved source."
  [id {:keys [person-id title year editor]}]
  (jdbc/execute!
   db/ds
   ["UPDATE AGORA_SOURCE SET person_id = ?, title = ?, year = ?, editor = ? WHERE id = ?"
    person-id
    (str/trim (or title ""))
    year
    (some-> editor
            str/trim
            not-empty)
    id])
  (cache/evict! source-cache id)
  (by-id id))

(defn search
  "Sources matching any subset of {:author :title :year} (ANDed); all blank → []. Author
  matches the joined AGORA_USER display name. Resolved rows, LIMIT 30."
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
