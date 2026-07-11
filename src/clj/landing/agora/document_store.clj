(ns landing.agora.document-store
  "Shared adapter for AGORA_DOCUMENT — the single polymorphic table holding every object
  type (all document types today; objections later). SQL + Caffeine caches around the pure
  domain (landing.agora.document-domain).

  A row keeps only the identity columns — `id`, `type` (object type, the T), `name`,
  `lang`, `major`, `minor` — plus two EDN blobs: immutable `content` and mutable
  `computed` ({:pins {tnlr-key → id}}). Type-specific view shaping and writes live in
  landing.agora.document, which calls these primitives so that all
  document I/O, successor indexing and cache eviction stay in one place (and one cache)."
  (:require
   [auto-core.log                 :as core-log]
   [clojure.edn                   :as edn]
   [landing.agora.cache           :as cache]
   [landing.agora.db              :as db]
   [landing.agora.document-domain :as domain]
   [next.jdbc                     :as jdbc]
   [next.jdbc.result-set          :as rs])
  (:import (java.sql SQLException)
           (java.time Instant)
           (java.time.temporal ChronoUnit)
           (java.util UUID)))

;; --- DB access with failure handling (→ 503 at the API boundary; see endpoints.error)

(defn- db-error!
  [e]
  (core-log/error-exception e "Agora DB error")
  (throw (ex-info "database unavailable" {:type ::db-unavailable} e)))

(defn q! [& args] (try (apply jdbc/execute! args) (catch SQLException e (db-error! e))))
(defn q1! [& args] (try (apply jdbc/execute-one! args) (catch SQLException e (db-error! e))))

(def kebab {:builder-fn rs/as-unqualified-kebab-maps})
(defn uuid [] (str (UUID/randomUUID)))
(defn now-iso [] (str (.truncatedTo (Instant/now) ChronoUnit/SECONDS)))

;; --- EDN (de)serialization of the content / computed blobs

(defn decode-content
  [s]
  (or (some-> s
              edn/read-string)
      {}))
(defn encode-content [m] (pr-str m))
(defn decode-pins
  [s]
  (:pins (or (some-> s
                     edn/read-string)
             {:pins {}})))
(defn encode-pins [pins] (pr-str {:pins pins}))

;; --- id → document (cached): identity columns + content + resolved pins

(defn- load-document
  [id]
  (when-let
    [row
     (q1!
      db/ds
      ["SELECT id, type, name, lang, major, minor, content, computed FROM AGORA_DOCUMENT WHERE id = ?"
       id]
      kebab)]
    (merge (select-keys row [:id :type :name :lang :major :minor])
           (decode-content (:content row))
           {:pins (decode-pins (:computed row))})))

(def ^:private document-cache
  "id → document (identity + immutable content + resolved pins)."
  (cache/loading 20000 load-document))

(defn fetch-document "The document for `id` (cached), or nil." [id] (cache/fetch document-cache id))

;; --- Lineage indexes (cached): successors, versions, translations.

(def ^:private successors-cache
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

(defn successors-of
  "ids of documents that declare `tnlr-key` as an input."
  [tnlr-key]
  (cache/fetch successors-cache tnlr-key))

(def ^:private versions-cache
  (cache/loading
   20000
   (fn [[ty nm lang major]]
     (q!
      db/ds
      ["SELECT id, minor FROM AGORA_DOCUMENT
         WHERE type = ? AND name = ? AND lang = ? AND major = ? ORDER BY minor"
       ty
       nm
       lang
       major]
      kebab))))

(defn versions-of
  "[{:id :minor} …] ascending for a TNLR lineage."
  [tnlr-key]
  (cache/fetch versions-cache tnlr-key))

(def ^:private translations-cache
  (cache/loading
   20000
   (fn [[ty nm lang]]
     (q!
      db/ds
      ["SELECT DISTINCT name, major, lang FROM AGORA_DOCUMENT
         WHERE type = ? AND name = ? AND lang <> ? ORDER BY lang"
       ty
       nm
       lang]
      kebab))))

(defn translations-of
  "other-language versions {:name :major :lang} of a concept."
  [ty nm lang]
  (cache/fetch translations-cache [ty nm lang]))

(defn resolve-latest-id
  "The id of the latest minor of (type, name, major) in `lang`, falling back to any
  other language when that concept is not yet translated."
  [ty nm major lang]
  (:id
   (q1!
    db/ds
    ["SELECT id FROM AGORA_DOCUMENT
       WHERE type = ? AND name = ? AND major = ? ORDER BY (lang = ?) DESC, minor DESC LIMIT 1"
     ty
     nm
     major
     lang]
    kebab)))

(defn- latest-of
  "The domain's injected `latest-of`: an input TNLR (carrying its own :type) → its
  current latest id."
  [{ty :type
    nm :name
    major :major
    lang :lang}]
  (resolve-latest-id ty nm major lang))

;; --- Cache invalidation

(defn evict-lineage!
  [ty nm lang major]
  (cache/evict! versions-cache [ty nm lang major])
  (cache/evict! translations-cache [ty nm lang]))

(defn clear-caches!
  []
  (cache/clear! document-cache)
  (cache/clear! successors-cache)
  (cache/clear! versions-cache)
  (cache/clear! translations-cache))

;; --- Successor index (reverse edges) + re-pin

(defn index-successors!
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

(defn repin-successors!
  "A new minor `new-id` was created for concept (type,name,lang,major): re-point every
  successor's pin for that TNLR to `new-id` (a mutable `computed` update — no version),
  and drop their cached documents."
  [ty nm lang major new-id]
  (let [t {:type ty
           :name nm
           :lang lang
           :major major}]
    (doseq [sid (cache/fetch successors-cache (domain/tnlr-key t))]
      (when-let [row (q1! db/ds ["SELECT computed FROM AGORA_DOCUMENT WHERE id = ?" sid] kebab)]
        (let [pins' (domain/repin (decode-pins (:computed row)) t new-id)]
          (q! db/ds ["UPDATE AGORA_DOCUMENT SET computed = ? WHERE id = ?" (encode-pins pins') sid])
          (cache/evict! document-cache sid))))))

;; --- Write

(defn author-name
  [owner-id]
  (when owner-id
    (:display-name
     (q1! db/ds ["SELECT display_name FROM AGORA_USER WHERE id = ?" owner-id] kebab))))

(defn next-minor
  [ty nm lang major]
  (:m
   (q1!
    db/ds
    ["SELECT COALESCE(MAX(minor) + 1, 0) AS m FROM AGORA_DOCUMENT
       WHERE type = ? AND name = ? AND lang = ? AND major = ?"
     ty
     nm
     lang
     major]
    kebab)))

(defn lang-exists?
  [ty nm major lang]
  (some?
   (q1!
    db/ds
    ["SELECT id FROM AGORA_DOCUMENT WHERE type = ? AND name = ? AND major = ? AND lang = ? LIMIT 1"
     ty
     nm
     major
     lang]
    kebab)))

(defn cid-taken?
  "True when any document already uses `cid` as its `name` (identity key) — so a freshly
  generated cid can be checked for the (negligible) chance of collision."
  [cid]
  (some? (q1! db/ds ["SELECT id FROM AGORA_DOCUMENT WHERE name = ? LIMIT 1" cid] kebab)))

(defn insert-document!
  "Insert one immutable version. `ident` = {:id :type :name :lang :major :minor};
  `content` is the immutable map (incl. its declared `:inputs`); pins are resolved from
  those declarations, and the declared inputs are indexed as successors."
  [{:keys [id name lang major minor]
    doc-type :type}
   content]
  (let [tnlrs (:inputs content)]
    (q!
     db/ds
     ["INSERT INTO AGORA_DOCUMENT
         (id, type, name, lang, major, minor, content, computed, published_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
      id
      doc-type
      name
      lang
      major
      minor
      (encode-content content)
      (encode-pins (domain/pin-all tnlrs latest-of))
      ;; denormalized copy of content.:published-at for sortable/rangeable reads
      (:published-at content)])
    (index-successors! id tnlrs)))

(defn backfill-published-at!
  "One-off: populate the denormalized `published_at` column from each row's
  `content.:published-at`. Run once from a REPL after applying migration 004; new writes
  set it directly. Idempotent — only touches rows where the column is still NULL. Returns
  the number of rows updated."
  []
  (reduce (fn [n {:keys [id content]}]
            (if-let [p (:published-at (decode-content content))]
              (do (q! db/ds ["UPDATE AGORA_DOCUMENT SET published_at = ? WHERE id = ?" p id])
                  (inc n))
              n))
          0
          (q! db/ds ["SELECT id, content FROM AGORA_DOCUMENT WHERE published_at IS NULL"] kebab)))

(defn rebuild-successor-index!
  "Recompute AGORA_SUCCESSOR from every document's declared inputs, and drop the in-memory
  caches. Safe to run any time; run daily to heal drift."
  []
  (q! db/ds ["DELETE FROM AGORA_SUCCESSOR"])
  (doseq [{:keys [id content]} (q! db/ds ["SELECT id, content FROM AGORA_DOCUMENT"] kebab)]
    (index-successors! id (:inputs (decode-content content))))
  (clear-caches!)
  :ok)

(defn rebuild-pins!
  "Recompute every document's `computed.:pins` from its declared `content.:inputs` — exactly
  what `insert-document!` does on write — healing any pin drift (e.g. a successor whose
  `repin-successors!` was missed while the reverse index was stale). Independent of
  AGORA_SUCCESSOR: each doc's pins are resolved straight from its own inputs via `latest-of`,
  so it needs no prior successor rebuild. Only rows whose pins actually change are written.
  Drops the caches. Returns the number of documents re-pinned."
  []
  (let [changed (reduce (fn [n {:keys [id content computed]}]
                          (let [pins (decode-pins computed)
                                pins' (domain/pin-all (:inputs (decode-content content)) latest-of)]
                            (if (= pins pins')
                              n
                              (do (q! db/ds
                                      ["UPDATE AGORA_DOCUMENT SET computed = ? WHERE id = ?"
                                       (encode-pins pins')
                                       id])
                                  (inc n)))))
                        0
                        (q! db/ds ["SELECT id, content, computed FROM AGORA_DOCUMENT"] kebab))]
    (clear-caches!)
    changed))
