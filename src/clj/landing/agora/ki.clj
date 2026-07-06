(ns landing.agora.ki
  "Adapter: SQL + Caffeine cache around the pure domain (landing.agora.domain).

  A node row keeps only the identity keys as columns — `id`, `type` (object type, the
  T), `name`, `lang`, `major`, `minor` — plus two EDN blobs:
   - `content`  (immutable): {:kind :title :statement :author :owner-id :published-at
                              :inputs [TNLR…]} — the declared inputs are authored, so
                              they live here; changing them versions the KI.
   - `computed` (mutable):   {:pins {tnlr-key → id}} — the resolved pin per declaration,
                              re-resolved on writes (no version).
  Reads assemble a document by `id` through the caches; the reverse direction lives in
  the `AGORA_SUCCESSOR` cache table. All graph decisions are in the domain; this ns
  only does I/O and EDN (de)serialization."
  (:require
   [auto-core.log        :as core-log]
   [clojure.edn          :as edn]
   [landing.agora.cache  :as cache]
   [landing.agora.db     :as db]
   [landing.agora.domain :as domain]
   [landing.language     :as language]
   [next.jdbc            :as jdbc]
   [next.jdbc.result-set :as rs])
  (:import (java.sql SQLException)
           (java.time Instant)
           (java.time.temporal ChronoUnit)
           (java.util UUID)))

;; ---------------------------------------------------------------------------
;; DB access with failure handling (→ 503 at the API boundary; see endpoints.error)
;; ---------------------------------------------------------------------------

(defn- db-error!
  [e]
  (core-log/error-exception e "Agora DB error")
  (throw (ex-info "database unavailable" {:type ::db-unavailable} e)))

(defn- q! [& args] (try (apply jdbc/execute! args) (catch SQLException e (db-error! e))))
(defn- q1! [& args] (try (apply jdbc/execute-one! args) (catch SQLException e (db-error! e))))

(def ^:private kebab {:builder-fn rs/as-unqualified-kebab-maps})
(defn- uuid [] (str (UUID/randomUUID)))
(defn- now-iso [] (str (.truncatedTo (Instant/now) ChronoUnit/SECONDS)))

;; ---------------------------------------------------------------------------
;; EDN (de)serialization of the content / computed blobs (adapter concern)
;; ---------------------------------------------------------------------------

(defn- decode-content
  [s]
  (or (some-> s
              edn/read-string)
      {}))
(defn- encode-content [m] (pr-str m))
(defn- decode-pins
  [s]
  (:pins (or (some-> s
                     edn/read-string)
             {:pins {}})))
(defn- encode-pins [pins] (pr-str {:pins pins}))

;; ---------------------------------------------------------------------------
;; id → document (cached): identity columns + content + resolved pins
;; ---------------------------------------------------------------------------

(defn- load-node
  [id]
  (when-let
    [row
     (q1!
      db/ds
      ["SELECT id, type, name, lang, major, minor, content, computed
                        FROM AGORA_NODE WHERE id = ?"
       id]
      kebab)]
    (merge (select-keys row [:id :type :name :lang :major :minor])
           (decode-content (:content row))
           {:pins (decode-pins (:computed row))})))

(def ^:private node-cache
  "id → node document (identity + immutable content + resolved pins). Invalidated only
  when the mutable pins are re-resolved; content never changes."
  (cache/loading 20000 load-node))

(defn fetch-node "The node document for `id` (cached), or nil." [id] (cache/fetch node-cache id))

;; ---------------------------------------------------------------------------
;; Lineage indexes (cached): successors, versions, translations.
;; ---------------------------------------------------------------------------

(def ^:private successors-cache
  "TNLR [type name lang major] → ids of KIs that declare it as an input (AGORA_SUCCESSOR)."
  (cache/loading
   20000
   (fn [[ty nm lang major]]
     (mapv
      :successor-id
      (q!
       db/ds
       ["SELECT successor_id FROM AGORA_SUCCESSOR
                 WHERE input_type = ? AND input_name = ? AND input_lang = ? AND input_major = ?"
        ty
        nm
        lang
        major]
       kebab)))))

(def ^:private versions-cache
  "TNLR → [{:id :minor} …] ascending (the minor lineage in one language)."
  (cache/loading
   20000
   (fn [[ty nm lang major]]
     (q!
      db/ds
      ["SELECT id, minor FROM AGORA_NODE
           WHERE type = ? AND name = ? AND lang = ? AND major = ? ORDER BY minor"
       ty
       nm
       lang
       major]
      kebab))))

(def ^:private translations-cache
  "[type name lang] → other-language versions {:name :major :lang} of the same concept."
  (cache/loading
   20000
   (fn [[ty nm lang]]
     (q!
      db/ds
      ["SELECT DISTINCT name, major, lang FROM AGORA_NODE
           WHERE type = ? AND name = ? AND lang <> ? ORDER BY lang"
       ty
       nm
       lang]
      kebab))))

(defn- resolve-latest-id
  "The id of the latest minor of (name, major) in `lang`, falling back to any other
  language when that concept is not yet translated."
  [ki-name ki-major lang]
  (:id
   (q1!
    db/ds
    ["SELECT id FROM AGORA_NODE
              WHERE type = 'ki' AND name = ? AND major = ?
              ORDER BY (lang = ?) DESC, minor DESC LIMIT 1"
     ki-name
     ki-major
     lang]
    kebab)))

(defn- latest-of
  "The domain's injected `latest-of`: a TNLR map → its current latest id."
  [{:keys [name major lang]}]
  (resolve-latest-id name major lang))

;; ---------------------------------------------------------------------------
;; Cache invalidation
;; ---------------------------------------------------------------------------

(defn- evict-lineage!
  [ty nm lang major]
  (cache/evict! versions-cache [ty nm lang major])
  (cache/evict! translations-cache [ty nm lang]))

(defn- clear-caches!
  []
  (cache/clear! node-cache)
  (cache/clear! successors-cache)
  (cache/clear! versions-cache)
  (cache/clear! translations-cache))

;; ---------------------------------------------------------------------------
;; Read: assemble the endpoint-facing KI view
;; ---------------------------------------------------------------------------

(defn fetch-ki
  "The KI `id` as the endpoint view. Neighbours are light refs (inputs carry their
  TNLR + pinned id, successors only the id); the SPA fetches each by id for its card.
  Plus the version lineage and translations. nil if unknown."
  [id]
  (when-let [n (fetch-node id)]
    (let [tnlr (domain/tnlr-key n)]
      (-> n
          (assoc :output-statement (:statement n)
                 :inputs (domain/input-refs (:inputs n) (:pins n))
                 :successors (mapv (fn [sid] {:id sid}) (cache/fetch successors-cache tnlr))
                 :versions (cache/fetch versions-cache tnlr)
                 :translations (cache/fetch translations-cache [(:type n) (:name n) (:lang n)]))
          (dissoc :statement :owner-id :pins)))))

(defn fetch-ki-by-major
  "The latest-minor KI of (name, major) in `lang` (with cross-language fallback), or
  nil. The permanent public identity behind /agora/{lang}/ki/{name}/{major}."
  [ki-name ki-major lang]
  (when-let [id (resolve-latest-id ki-name ki-major lang)] (fetch-ki id)))

;; ---------------------------------------------------------------------------
;; Successor index (reverse edges) + re-pin
;; ---------------------------------------------------------------------------

(defn- index-successors!
  "Record, in AGORA_SUCCESSOR, that `successor-id` declares each of `tnlrs` as input."
  [successor-id tnlrs]
  (doseq [{:keys [tnlr]} (domain/successor-tuples successor-id tnlrs)]
    (let [[ty nm lang major] (domain/tnlr-key tnlr)]
      (q!
       db/ds
       ["INSERT IGNORE INTO AGORA_SUCCESSOR
            (input_type, input_name, input_lang, input_major, successor_id) VALUES (?, ?, ?, ?, ?)"
        ty
        nm
        lang
        major
        successor-id])
      (cache/evict! successors-cache [ty nm lang major]))))

(defn- repin-successors!
  "A new minor `new-id` was created for concept (type,name,lang,major): re-point every
  successor's pin for that TNLR to `new-id` (a mutable `computed` update — no version),
  and drop their cached documents."
  [ty nm lang major new-id]
  (let [t {:type ty
           :name nm
           :lang lang
           :major major}]
    (doseq [sid (cache/fetch successors-cache (domain/tnlr-key t))]
      (when-let [row (q1! db/ds ["SELECT computed FROM AGORA_NODE WHERE id = ?" sid] kebab)]
        (let [pins' (domain/repin (decode-pins (:computed row)) t new-id)]
          (q! db/ds ["UPDATE AGORA_NODE SET computed = ? WHERE id = ?" (encode-pins pins') sid])
          (cache/evict! node-cache sid))))))

;; ---------------------------------------------------------------------------
;; Write
;; ---------------------------------------------------------------------------

(defn- author-name
  [owner-id]
  (when owner-id
    (:display-name
     (q1! db/ds ["SELECT display_name FROM AGORA_USER WHERE id = ?" owner-id] kebab))))

(defn- next-minor
  [ty nm lang major]
  (:m
   (q1!
    db/ds
    ["SELECT COALESCE(MAX(minor) + 1, 0) AS m FROM AGORA_NODE
             WHERE type = ? AND name = ? AND lang = ? AND major = ?"
     ty
     nm
     lang
     major]
    kebab)))

(defn- lang-exists?
  [ty nm major lang]
  (some?
   (q1! db/ds
        ["SELECT id FROM AGORA_NODE WHERE type = ? AND name = ? AND major = ? AND lang = ? LIMIT 1"
         ty
         nm
         major
         lang]
        kebab)))

(defn- insert-node!
  "Insert one immutable version. `identity` = {:id :name :lang :major :minor}; `content`
  is the immutable map (incl. its declared `:inputs`); pins are resolved from those
  declarations. Indexes the declared inputs as successors."
  [{:keys [id name lang major minor]} content]
  (let [tnlrs (:inputs content)]
    (q!
     db/ds
     ["INSERT INTO AGORA_NODE (id, type, name, lang, major, minor, content, computed)
          VALUES (?, 'ki', ?, ?, ?, ?, ?, ?)"
      id
      name
      lang
      major
      minor
      (encode-content content)
      (encode-pins (domain/pin-all tnlrs latest-of))])
    (index-successors! id tnlrs)))

(defn- content-of
  "The immutable content map carried forward from a source node (its authored fields)."
  [n]
  (select-keys n [:kind :title :statement :author :owner-id :inputs]))

(defn create-ki
  "Create a brand-new KI (major 1, minor 0), no inputs yet. Returns it via fetch-ki."
  [owner-id {ki-name :name
             ki-title :title
             kind :kind
             ki-lang :lang
             statement :output-statement}]
  (let [id (uuid)
        lang (or ki-lang language/default-lang)]
    (insert-node! {:id id
                   :name ki-name
                   :lang lang
                   :major 1
                   :minor 0}
                  {:kind kind
                   :title ki-title
                   :statement statement
                   :inputs []
                   :author (author-name owner-id)
                   :owner-id owner-id
                   :published-at (now-iso)})
    (evict-lineage! "ki" ki-name lang 1)
    (fetch-ki id)))

(defn- new-minor!
  "Insert a new minor of the concept `src` (same name/major/lang) with the given
  `content` (declared inputs carried in it), re-pin this concept's successors onto it,
  and return the new KI."
  [src content]
  (let [{:keys [name lang major]} src
        new-id (uuid)]
    (insert-node! {:id new-id
                   :name name
                   :lang lang
                   :major major
                   :minor (next-minor "ki" name lang major)}
                  (assoc content :published-at (now-iso)))
    (repin-successors! "ki" name lang major new-id)
    (evict-lineage! "ki" name lang major)
    (fetch-ki new-id)))

(defn edit-ki
  "Edit KI `id` → a new minor version (statement/title/kind change; declared inputs
  carried forward)."
  [id
   owner-id
   {kind :kind
    ki-title :title
    statement :output-statement}]
  (when-let [src (fetch-node id)]
    (new-minor! src
                (assoc (content-of src)
                       :kind kind
                       :title ki-title
                       :statement statement
                       :author (author-name owner-id)
                       :owner-id owner-id))))

(defn add-input
  "Declare the KI referenced by `input` (name+major, in this KI's language) as an input
  of `id` → a new minor. Idempotent. Returns the updated (new-minor) KI."
  [id
   owner-id
   {in-name :name
    in-major :major}]
  (when-let [{:keys [lang]
              :as src}
             (fetch-node id)]
    (if (resolve-latest-id in-name in-major lang)
      (let [t {:type "ki"
               :name in-name
               :lang lang
               :major in-major}]
        (new-minor! src
                    (assoc (content-of src)
                           :inputs (domain/add-declared (:inputs src) t)
                           :author (author-name owner-id)
                           :owner-id owner-id)))
      (fetch-ki id))))

(defn drop-input
  "Remove the declared input `input` (name+major) from KI `id` → a new minor. Returns
  the updated (new-minor) KI."
  [id
   owner-id
   {in-name :name
    in-major :major}]
  (when-let [{:keys [lang]
              :as src}
             (fetch-node id)]
    (let [t {:type "ki"
             :name in-name
             :lang lang
             :major in-major}]
      (new-minor! src
                  (assoc (content-of src)
                         :inputs (domain/drop-declared (:inputs src) t)
                         :author (author-name owner-id)
                         :owner-id owner-id)))))

(defn translate-ki
  "Create a `to-lang` version of KI `id` and to-lang copies of its direct inputs
  (declared+pinned to those). Existing versions are untouched. Returns the to-lang KI."
  [id to-lang owner-id title statement]
  (when-let [{:keys [name major]
              :as src}
             (fetch-node id)]
    (when-not (lang-exists? "ki" name major to-lang)
      (let [author (author-name owner-id)
            declarations (mapv (fn [{in-name :name
                                     in-major :major
                                     in-lang :lang}]
                                 (when-not (lang-exists? "ki" in-name in-major to-lang)
                                   (when-let [s (fetch-node
                                                 (resolve-latest-id in-name in-major in-lang))]
                                     (insert-node! {:id (uuid)
                                                    :name in-name
                                                    :lang to-lang
                                                    :major in-major
                                                    :minor 0}
                                                   {:kind (:kind s)
                                                    :title (:title s)
                                                    :statement (:statement s)
                                                    :inputs []
                                                    :author author
                                                    :owner-id owner-id
                                                    :published-at (now-iso)})
                                     (evict-lineage! "ki" in-name to-lang in-major)))
                                 {:type "ki"
                                  :name in-name
                                  :lang to-lang
                                  :major in-major})
                               (:inputs src))]
        (insert-node! {:id (uuid)
                       :name name
                       :lang to-lang
                       :major major
                       :minor 0}
                      {:kind (:kind src)
                       :title (if (nil? title) (:title src) title)
                       :statement (if (nil? statement) (:statement src) statement)
                       :inputs declarations
                       :author author
                       :owner-id owner-id
                       :published-at (now-iso)})
        (evict-lineage! "ki" name to-lang major)))
    (fetch-ki-by-major name major to-lang)))

;; ---------------------------------------------------------------------------
;; Discovery / search / admin
;; ---------------------------------------------------------------------------

(defn- card
  "A discovery/search card from a row with a `content` blob."
  [row]
  (let [c (decode-content (:content row))]
    (assoc (select-keys row [:id :type :name :lang :major :minor])
           :kind (:kind c)
           :title (:title c)
           :output-statement (:statement c))))

(defn search-kis
  "KIs matching `q` in name/title/statement, latest minor per lineage, scoped to `lang`.
  Blank `q` → []. Title/statement live in the content blob, so this is a LIKE scan."
  [q lang]
  (if (or (nil? q) (empty? q))
    []
    (let [like (str "%" q "%")]
      (mapv
       card
       (q!
        db/ds
        ["SELECT k.id, k.type, k.name, k.lang, k.major, k.minor, k.content FROM AGORA_NODE k
                  WHERE k.type = 'ki' AND k.lang = ?
                    AND (k.name LIKE ? OR k.content LIKE ?)
                    AND k.minor = (SELECT MAX(k2.minor) FROM AGORA_NODE k2
                                   WHERE k2.type = 'ki' AND k2.name = k.name
                                     AND k2.major = k.major AND k2.lang = k.lang)
                  ORDER BY k.name, k.major LIMIT 50"
         lang
         like
         like]
        kebab)))))

(defn list-kis
  "Up to 10 latest-minor KIs (with :output-statement for cards), sampled at random,
  scoped to `lang`."
  [lang]
  (mapv
   card
   (q!
    db/ds
    ["SELECT k.id, k.type, k.name, k.lang, k.major, k.minor, k.content FROM AGORA_NODE k
              WHERE k.type = 'ki' AND k.lang = ?
                AND k.minor = (SELECT MAX(k2.minor) FROM AGORA_NODE k2
                               WHERE k2.type = 'ki' AND k2.name = k.name
                                 AND k2.major = k.major AND k2.lang = k.lang)
              ORDER BY RAND() LIMIT 10"
     lang]
    kebab)))

(defn list-tnrs
  "All KI lineages (name, major) with version/language/latest counts (admin page)."
  []
  (q!
   db/ds
   ["SELECT name, major, COUNT(*) AS versions, COUNT(DISTINCT lang) AS langs, MAX(minor) AS latest
        FROM AGORA_NODE WHERE type = 'ki' GROUP BY name, major ORDER BY name, major"]
   kebab))

(defn delete-tnr!
  "Drop an entire (name, major) lineage plus its successor-index rows. Returns the
  number of node rows deleted."
  [ki-name ki-major]
  (q!
   db/ds
   ["DELETE FROM AGORA_SUCCESSOR
              WHERE (input_name = ? AND input_major = ?)
                 OR successor_id IN (SELECT id FROM AGORA_NODE WHERE name = ? AND major = ?)"
    ki-name
    ki-major
    ki-name
    ki-major])
  (let [n (:next.jdbc/update-count
           (q1! db/ds
                ["DELETE FROM AGORA_NODE WHERE type = 'ki' AND name = ? AND major = ?"
                 ki-name
                 ki-major]))]
    (clear-caches!)
    n))

(defn compact-tnr!
  "Keep only the latest minor of each language of a (name, major) lineage; delete the
  rest. Returns the number of node rows deleted."
  [ki-name ki-major]
  (let
    [n
     (->>
       (q!
        db/ds
        ["SELECT lang, MAX(minor) AS latest FROM AGORA_NODE
                     WHERE type = 'ki' AND name = ? AND major = ? GROUP BY lang"
         ki-name
         ki-major]
        kebab)
       (reduce
        (fn [acc {:keys [lang latest]}]
          (+
           acc
           (or
            (:next.jdbc/update-count
             (q1!
              db/ds
              ["DELETE FROM AGORA_NODE
                                                  WHERE type = 'ki' AND name = ? AND major = ?
                                                    AND lang = ? AND minor < ?"
               ki-name
               ki-major
               lang
               latest]))
            0)))
        0))]
    (clear-caches!)
    n))

(defn sitemap-rows
  "Every public KI permalink as {:name :major :lang :lastmod}. published-at lives in the
  content blob, so the max per lineage is computed here."
  []
  (->> (q! db/ds ["SELECT name, major, lang, content FROM AGORA_NODE WHERE type = 'ki'"] kebab)
       (map (fn [r]
              (assoc (select-keys r [:name :major :lang])
                     :published-at
                     (:published-at (decode-content (:content r))))))
       (group-by (juxt :name :major :lang))
       (mapv (fn [[[name major lang] rows]]
               {:name name
                :major major
                :lang lang
                :lastmod (apply max-key str (map :published-at rows))}))))

;; ---------------------------------------------------------------------------
;; Daily cache rebuild
;; ---------------------------------------------------------------------------

(defn rebuild-successor-index!
  "Recompute AGORA_SUCCESSOR from every node's declared inputs, and drop the in-memory
  caches. Safe to run any time; run daily to heal drift."
  []
  (q! db/ds ["DELETE FROM AGORA_SUCCESSOR"])
  (doseq [{:keys [id content]} (q! db/ds ["SELECT id, content FROM AGORA_NODE"] kebab)]
    (index-successors! id (:inputs (decode-content content))))
  (clear-caches!)
  :ok)
