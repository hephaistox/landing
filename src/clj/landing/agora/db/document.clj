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

(defn fetch
  "Document for `id`, or nil."
  [id]
  (some-> (q1! db/ds [(str select-doc "WHERE id = ?") id] kebab)
          row->doc))

(defn published-of-tnr
  "Every **published** document of a TNR (a ref's type, name, major) — all languages, all minors — as
  full docs. One query; the caller (`lineage/resolve-latest`) picks which to serve. Drafts excluded:
  a draft only surfaces by exact id inside a change."
  [{:keys [type name major]}]
  (mapv row->doc
        (q! db/ds
            [(str select-doc "WHERE type = ? AND name = ? AND major = ? AND draft = 0")
             (t->s type)
             name
             major]
            kebab)))

;; --- lineage indexes ------------------------------------------------------------------------

(defn versions
  "Every minor of a lineage — a `ref`'s (type, name, lang, major): `[{:id :minor :draft
  :publication-id} …]`, ascending, drafts included. The caller picks the version; SQL never resolves."
  [{:keys [type name lang major]}]
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
     name
     (t->s lang)
     major]
    kebab)))

(comment
  (versions {:type :article
             :name "hzkr69fHJl"
             :lang :fr
             :major 1})
  ;;
)

(defn successor-ids
  "Ids that declare a lineage — a `ref`'s (type, name, lang, major) — as an input. The reverse edge,
  from AGORA_SUCCESSOR."
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

(comment
  (successor-ids {:type :ki
                  :name "hT5A3WClsI"
                  :lang :fr
                  :major 1})
  ;;
)

(defn translations
  "Published language siblings `{:name :major :lang}` of a `ref` — the same (type, name, major) in
  other languages, excluding the ref's own `lang`."
  [{:keys [type name lang major]}]
  (mapv
   #(update % :lang keyword)
   (q!
    db/ds
    ["SELECT DISTINCT name, major, lang FROM AGORA_DOCUMENT
             WHERE type = ? AND name = ? AND major = ? AND lang <> ? AND draft = 0 ORDER BY lang"
     (t->s type)
     name
     major
     (t->s lang)]
    kebab)))
