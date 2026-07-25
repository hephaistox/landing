(ns landing.agora.db.document
  "New Wire. Raw AGORA_DOCUMENT SQL, plus the connection and EDN codec the queries share. No cache.
  No domain rules.

  The `type` and `lang` columns are text (`\"ki\"`, `\"fr\"`); the domain uses keywords (`:ki`,
  `:fr`). `fetch` keywords them on read; queries coerce back with `t->s`. Other fields are returned
  as stored."
  (:require
   [auto-core.log        :as core-log]
   [clojure.edn          :as edn]
   [landing.agora.db     :as db]
   [next.jdbc            :as jdbc]
   [next.jdbc.result-set :as rs])
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
  "A raw row → a full document: identity columns (`:type` and `:lang` keyworded), decoded `content`,
  resolved `:pins`."
  [row]
  (merge (-> (select-keys row [:id :type :name :lang :major :minor])
             (update :type keyword)
             (update :lang keyword))
         {:draft (truthy? (:draft row))
          :publication-id (:publication-id row)}
         (decode-content (:content row))
         {:pins (decode-pins (:computed row))}))

(defn fetch-id
  "Document for `id`, or nil."
  [id]
  (some-> (q1! db/ds [(str select-doc "WHERE id = ?") id] kebab)
          row->doc))

;; --- lineage indexes ------------------------------------------------------------------------

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
  "For a `ref` (an input lineage's type, name, lang, major), the id of each **successor lineage's
  latest published minor** — the reverse edge, resolved in SQL. A successor lineage appears only if
  its *latest* published minor still declares `ref` as an input (a later minor that dropped the input
  is excluded); drafts never match. One id per successor lineage, no domain collapse."
  [{:keys [type name lang major]}]
  (mapv
   :id
   (q!
    db/ds
    ["SELECT DISTINCT d.id
        FROM AGORA_SUCCESSOR s
        JOIN AGORA_DOCUMENT d ON d.id = s.successor_id
       WHERE s.input_type = ? AND s.input_name = ? AND s.input_lang = ? AND s.input_major = ?
         AND d.draft = 0
         AND d.minor = (SELECT MAX(d2.minor) FROM AGORA_DOCUMENT d2
                         WHERE d2.type = d.type AND d2.name = d.name
                           AND d2.lang = d.lang AND d2.major = d.major AND d2.draft = 0)"
     (t->s type)
     name
     (t->s lang)
     major]
    kebab)))


