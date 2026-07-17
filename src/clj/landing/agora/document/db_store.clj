(ns landing.agora.document.db-store
  "The **raw DB-query layer** for AGORA_DOCUMENT — the bare SQL statements plus the connection
  primitives and EDN (de)serialization they share. **No caching, no domain logic**: it sits at the
  bottom, `landing.agora.document.store` wraps these queries in Caffeine caches + write
  orchestration, and `landing.agora.document` composes on top. The intent is that *every*
  AGORA_DOCUMENT query lives here, so the persistence surface is one namespace — queries move in
  from `store`/`document` one at a time."
  (:require
   [auto-core.log        :as core-log]
   [clojure.edn          :as edn]
   [landing.agora.db     :as db]
   [next.jdbc            :as jdbc]
   [next.jdbc.result-set :as rs])
  (:import (java.sql SQLException)
           (java.time Instant)
           (java.time.temporal ChronoUnit)
           (java.util UUID)))

;; --- DB access with failure handling (→ 503 at the API boundary; see endpoints.error)

(defn db-error!
  [e]
  (core-log/error-exception e "Agora DB error")
  (throw (ex-info "database unavailable" {:type ::db-unavailable} e)))

(defn q! [& args] (try (apply jdbc/execute! args) (catch SQLException e (db-error! e))))
(defn q1! [& args] (try (apply jdbc/execute-one! args) (catch SQLException e (db-error! e))))

(def kebab {:builder-fn rs/as-unqualified-kebab-maps})

(defn t->s
  "An object type → its DB `type`-column **string** form. The domain carries object types as
  keywords (`:ki`), but the column is text — so every SQL type parameter passes through here.
  Accepts a keyword or an already-string type (nil-safe)."
  [t]
  (some-> t
          name))

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

(defn truthy?
  "MySQL TINYINT(1) comes back as a Boolean or a 0/1 number depending on the driver flags —
  normalize either to a real boolean."
  [v]
  (if (number? v) (not (zero? v)) (boolean v)))

;; --- fetch: id → document (identity columns + decoded content + resolved pins)

(defn load-document
  "The document for `id` — its identity columns + decoded immutable `content` + resolved `:pins` —
  or nil. The raw fetch query; `store/fetch-document` caches it."
  [id]
  (when-let
    [row
     (q1!
      db/ds
      ["SELECT id, type, name, lang, major, minor, draft, publication_id, content, computed
         FROM AGORA_DOCUMENT WHERE id = ?"
       id]
      kebab)]
    (merge (update (select-keys row [:id :type :name :lang :major :minor]) :type keyword)
           {:draft (truthy? (:draft row))
            :publication-id (:publication-id row)}
           (decode-content (:content row))
           {:pins (decode-pins (:computed row))})))

(defn documents
  "All versions (minors) of the lineage identified by the TNLR (type, name, lang, major) —
  `[{:id :minor :draft :publication-id} …]` ascending by minor, **drafts included**. The raw fetch
  only; the caller resolves *which* version it wants with the `lineage` rules (`latest-published`,
  `latest-with-drafts`, `draft-in-publication`) — resolution lives in Clojure, never in SQL."
  [type doc-name lang major]
  (mapv
   (fn [r]
     {:id (:id r)
      :minor (:minor r)
      :draft (truthy? (:draft r))
      :publication-id (:publication-id r)})
   (q!
    db/ds
    ["SELECT id, minor, draft, publication_id FROM AGORA_DOCUMENT
             WHERE type = ? AND name = ? AND lang = ? AND major = ? ORDER BY minor"
     (t->s type)
     doc-name
     lang
     major]
    kebab)))

;; --- creation: insert one immutable version row

(defn insert-row!
  "Insert one immutable AGORA_DOCUMENT version. `ident` = {:id :type :name :lang :major :minor
  :draft? :publication-id}; the immutable `content` map and the resolved `pins` ({tnlr-key → id})
  are EDN-encoded here, and `content.:published-at` is denormalized into its own sortable column.
  Pure persistence — the caller resolves the pins and indexes the successors (see
  `store/insert-document!`)."
  [{:keys [id name lang major minor draft? publication-id]
    doc-type :type}
   content
   pins]
  (q!
   db/ds
   ["INSERT INTO AGORA_DOCUMENT
       (id, type, name, lang, major, minor, draft, content, computed, published_at, publication_id)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    id
    (t->s doc-type)
    name
    lang
    major
    minor
    (if draft? 1 0)
    (encode-content content)
    (encode-pins pins)
    (:published-at content)
    publication-id]))

;; --- admin lineage queries -----------------------------------------------------

(defn all-tnr-rows
  "Every lineage of every type, **one row per language version** — (type, name, lang, major) with
  its version count, latest minor, and the latest-minor's raw `content` blob. One self-join query
  pinning each lineage's latest-minor row; the caller decodes `content` for the admin table's
  title/kind."
  []
  (q!
   db/ds
   ["SELECT d.type, d.name, d.lang, d.major, d.content, g.versions, g.latest
       FROM AGORA_DOCUMENT d
       JOIN (SELECT type, name, lang, major, COUNT(*) AS versions, MAX(minor) AS latest
               FROM AGORA_DOCUMENT
              GROUP BY type, name, lang, major) g
         ON d.type = g.type AND d.name = g.name AND d.lang = g.lang
            AND d.major = g.major AND d.minor = g.latest
      ORDER BY d.type, d.name, d.major, d.lang"]
   kebab))

(defn delete-lineage!
  "Drop a single language version of a lineage — (type, name, lang, major) — plus its
  successor-index rows (targeted; edges before nodes so nothing dangles). Returns rows removed."
  [type doc-name lang doc-major]
  (q!
   db/ds
   ["DELETE FROM AGORA_SUCCESSOR
      WHERE (input_type = ? AND input_name = ? AND input_lang = ? AND input_major = ?)
         OR successor_id IN (SELECT id FROM AGORA_DOCUMENT
                             WHERE type = ? AND name = ? AND lang = ? AND major = ?)"
    (t->s type)
    doc-name
    lang
    doc-major
    (t->s type)
    doc-name
    lang
    doc-major])
  (:next.jdbc/update-count
   (q1! db/ds
        ["DELETE FROM AGORA_DOCUMENT WHERE type = ? AND name = ? AND lang = ? AND major = ?"
         (t->s type)
         doc-name
         lang
         doc-major])))

(defn compact-lineage!
  "Keep only the latest minor of one language version — (type, name, lang, major) — deleting the
  rest (their successor-index rows first, so nothing dangles). Returns rows removed."
  [type doc-name lang doc-major]
  (let
    [latest
     (:latest
      (q1!
       db/ds
       ["SELECT MAX(minor) AS latest FROM AGORA_DOCUMENT
                     WHERE type = ? AND name = ? AND lang = ? AND major = ?"
        (t->s type)
        doc-name
        lang
        doc-major]
       kebab))]
    (q!
     db/ds
     ["DELETE FROM AGORA_SUCCESSOR
         WHERE successor_id IN (SELECT id FROM AGORA_DOCUMENT
                                WHERE type = ? AND name = ? AND lang = ? AND major = ? AND minor < ?)"
      (t->s type)
      doc-name
      lang
      doc-major
      latest])
    (or
     (:next.jdbc/update-count
      (q1!
       db/ds
       ["DELETE FROM AGORA_DOCUMENT
              WHERE type = ? AND name = ? AND lang = ? AND major = ? AND minor < ?"
        (t->s type)
        doc-name
        lang
        doc-major
        latest]))
     0)))
