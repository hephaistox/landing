(ns landing.agora.source
  "Bibliographic sources — the shared *work* a `kind=source` citation references, stored in the
  dedicated `AGORA_SOURCE` table (a normalized, deduplicated reference row, not a versioned
  document). A work carries TWO people: its real-life **author** (`author_id`/`author_name`, an
  AGORA_USER, possibly login-less external) and the Agora **contributor** who added the record
  (`owner_id`, resolved to `owner_name` by a join). A citing document holds
  `content.:source = {:source-id :locator}`; `resolve-ref` turns that into the work's display fields
  plus this citation's locator, on read. A read-through cache fronts id → resolved source. No
  dependency on `document`/`engine` — the edge runs document → source, never the reverse."
  (:require
   [clojure.string       :as str]
   [landing.agora.cache  :as cache]
   [landing.agora.db     :as db]
   [next.jdbc            :as jdbc]
   [next.jdbc.result-set :as rs]))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(def ^:private select-source
  "SELECT s.source_id, s.author_id, s.author_name, s.title, s.year, s.editor, s.url,
          s.owner_id, u.display_name AS owner_name
     FROM AGORA_SOURCE s
     LEFT JOIN AGORA_USER u ON u.id = s.owner_id ")

(defn- shape
  "A DB row → resolved source. `:id` is the work's `source_id` (the wire contract). Both people are
  present: `:author-id`/`:author-name` (the work's real author) and `:owner-id`/`:owner-name` (the
  Agora contributor who added it)."
  [row]
  (when row
    (-> (select-keys row [:author-id :author-name :title :year :editor :url :owner-id :owner-name])
        (assoc :id (:source-id row)))))

(defn- load-source
  [id]
  (shape (jdbc/execute-one! db/ds [(str select-source "WHERE s.source_id = ?") id] opts)))

(def ^:private source-cache (cache/loading 20000 load-source))

(defn by-id "The resolved source `id` (cached), or nil." [id] (cache/fetch source-cache id))

(defn resolve-ref
  "Resolve a citing document's `content.:source` ref `{:source-id :locator}` to the work's display
  fields plus this citation's locator, or nil for a blank/unknown ref."
  [{:keys [source-id locator]}]
  (when-not (str/blank? source-id)
    (some-> (by-id source-id)
            (assoc :source-id source-id :locator locator))))

(defn search
  "Sources matching the provided `author`/`title`/`year` filters (AND-ed; all blank → `[]`), as
  resolved rows, capped at 30. Author/title match as substrings, year exactly."
  [{:keys [author title year]}]
  (let [filters (cond-> []
                  (not (str/blank? author)) (conj ["s.author_name LIKE ?"
                                                   (str "%" (str/trim author) "%")])
                  (not (str/blank? title)) (conj ["s.title LIKE ?" (str "%" (str/trim title) "%")])
                  (not (str/blank? year)) (conj ["s.year = ?" (parse-long (str/trim year))]))]
    (if (empty? filters)
      []
      (mapv shape
            (jdbc/execute! db/ds
                           (into [(str select-source
                                       "WHERE "
                                       (str/join " AND " (map first filters))
                                       " ORDER BY s.title LIMIT 30")]
                                 (map second filters))
                           opts)))))

(defn recent
  "The most recently added sources, for the picker's suggestions (LIMIT 10)."
  []
  (mapv shape
        (jdbc/execute! db/ds [(str select-source "ORDER BY s.created_at DESC LIMIT 10")] opts)))
