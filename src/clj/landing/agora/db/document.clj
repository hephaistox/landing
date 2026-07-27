(ns landing.agora.db.document
  "Raw AGORA_DOCUMENT SQL, plus the connection and EDN codec the queries share. No cache.
  No domain rules.

  The `type` and `lang` columns are text (`\"ki\"`, `\"fr\"`); the domain uses keywords (`:ki`,
  `:fr`). `fetch` keywords them on read; queries coerce back with `t->s`. Other fields are returned
  as stored."
  (:require
   [auto-core.log                   :as core-log]
   [clojure.edn                     :as edn]
   [landing.agora.db                :as db]
   [landing.agora.document.identity :as di]
   [next.jdbc                       :as jdbc]
   [next.jdbc.result-set            :as rs])
  (:import (java.sql SQLException)))

;; --- connection + failure handling. A DB error throws ::db-unavailable.

(defn- db-error!
  [e]
  (core-log/error-exception e "Agora DB error")
  (throw (ex-info "database unavailable" {:type ::db-unavailable} e)))

(defn- q! [& args] (try (apply jdbc/execute! args) (catch SQLException e (db-error! e))))
(defn- q1! [& args] (try (apply jdbc/execute-one! args) (catch SQLException e (db-error! e))))

(def ^:private kebab {:builder-fn rs/as-unqualified-kebab-maps})

(defn- t->s
  "Identity keyword (`:ki`, `:fr`) → its DB string. nil-safe."
  [t]
  (some-> t
          name))

;; --- EDN (de)serialization of the immutable `content` / mutable `computed` blobs

(defn- decode-content
  [s]
  (or (some-> s
              edn/read-string)
      {}))
(defn- decode-pins
  [s]
  (:pins (or (some-> s
                     edn/read-string)
             {:pins {}})))

(defn- truthy?
  "MySQL TINYINT(1) comes back as a Boolean or a 0/1 number. Normalize to a boolean."
  [v]
  (if (number? v) (not (zero? v)) (boolean v)))

;; --- id → full document(s) -------------------------------------------------------------------

(def ^:private select-doc
  "SELECT id, type, name, lang, major, minor, draft, publication_id, content, computed
     FROM AGORA_DOCUMENT ")

(defn- row->doc
  "A raw row → a full document: identity columns (`:type` and `:lang` keyworded), decoded `content`
  (its `:kind` keyworded), resolved `:pins`."
  [row]
  (let [content (decode-content (:content row))]
    (cond-> (merge (-> (select-keys row [:id :type :name :lang :major :minor])
                       (update :type keyword)
                       (update :lang keyword))
                   {:draft (truthy? (:draft row))
                    :publication-id (:publication-id row)}
                   content
                   {:pins (decode-pins (:computed row))})
      (:kind content) (update :kind keyword))))

(defn fetch-id
  "Document for `id`, or nil."
  [id]
  (some-> (q1! db/ds [(str select-doc "WHERE id = ?") id] kebab)
          row->doc))

(defn published-of-type
  "One page of the browse feed: the latest published minor of every lineage of `type` in `lang`,
  newest first (`published_at`), as full documents. `limit`/`offset` page it."
  [type lang limit offset]
  (mapv
   row->doc
   (q!
    db/ds
    ["SELECT d.id, d.type, d.name, d.lang, d.major, d.minor, d.draft, d.publication_id,
                     d.content, d.computed
                FROM AGORA_DOCUMENT d
                JOIN (SELECT type, name, lang, major, MAX(minor) AS latest
                        FROM AGORA_DOCUMENT
                       WHERE type = ? AND lang = ? AND draft = 0
                       GROUP BY type, name, lang, major) g
                  ON d.type = g.type AND d.name = g.name AND d.lang = g.lang
                     AND d.major = g.major AND d.minor = g.latest
               WHERE d.draft = 0
               ORDER BY d.published_at DESC
               LIMIT ? OFFSET ?"
     (t->s type)
     (t->s lang)
     limit
     offset]
    kebab)))

;; --- lineage indexes ------------------------------------------------------------------------

(defn rebuild-successor-index!
  "Recompute AGORA_SUCCESSOR from scratch: wipe it, then re-insert one reverse edge (input TNLR →
  successor id) per declared input of **each lineage's latest published minor** — older minors are
  not indexed (the documents themselves hold every minor's inputs). A derived cache, rebuilt from the
  documents. Returns the number of lineages scanned."
  []
  (q! db/ds ["DELETE FROM AGORA_SUCCESSOR"])
  (let
    [docs
     (q!
      db/ds
      ["SELECT d.id, d.content
                     FROM AGORA_DOCUMENT d
                     JOIN (SELECT type, name, lang, major, MAX(minor) AS latest
                             FROM AGORA_DOCUMENT WHERE draft = 0
                            GROUP BY type, name, lang, major) g
                       ON d.type = g.type AND d.name = g.name AND d.lang = g.lang
                          AND d.major = g.major AND d.minor = g.latest
                    WHERE d.draft = 0"]
      kebab)]
    (doseq [{:keys [id content]} docs
            {:keys [tnlr]} (di/successor-tuples id (:inputs (decode-content content)))
            :let [{:keys [type name lang major]} tnlr]]
      (q!
       db/ds
       ["INSERT IGNORE INTO AGORA_SUCCESSOR
              (input_type, input_name, input_lang, input_major, successor_id)
            VALUES (?, ?, ?, ?, ?)"
        (t->s type)
        name
        (t->s lang)
        major
        id]))
    (count docs)))

(defn latest-published-id
  "The `id` of the latest published minor of a lineage (a `ref`'s type, name, lang, major), or nil
  when it has no published minor in that language. A single indexed lookup — the caller caches the
  tnlr→id mapping. No cross-language fallback: a lineage absent in `lang` resolves to nil."
  [{:keys [type name lang major]}]
  (:id
   (q1!
    db/ds
    ["SELECT id FROM AGORA_DOCUMENT
                WHERE type = ? AND name = ? AND lang = ? AND major = ? AND draft = 0
                ORDER BY minor DESC LIMIT 1"
     (t->s type)
     name
     (t->s lang)
     major]
    kebab)))

(defn successor-latest-ids
  "For a `ref` (an input lineage's type, name, lang, major), the id of each successor lineage's
  latest published minor that declares `ref` as an input. AGORA_SUCCESSOR holds only those latest
  minors (see `rebuild-successor-index!`), so this is a plain reverse lookup — one id per successor
  lineage, no join or collapse."
  [{:keys [type name lang major]}]
  (mapv
   :successor-id
   (q!
    db/ds
    ["SELECT successor_id FROM AGORA_SUCCESSOR
            WHERE input_type = ? AND input_name = ? AND input_lang = ? AND input_major = ?"
     (t->s type)
     name
     (t->s lang)
     major]
    kebab)))
