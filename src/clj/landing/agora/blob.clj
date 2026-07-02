(ns landing.agora.blob
  "Content-addressed storage for immutable KI text.

  A blob is addressed by the SHA-256 hex digest of its content. Everywhere else
  the DB stores only that hash; this namespace is the single place raw content is
  read or written. Identical content dedupes automatically (the hash is the key).

  Backed by the AGORA_BLOB table in the shared MySQL DB for the MVP. `write-blob`
  and `read-blob` are the interface to keep stable if this moves to Cellar/S3."
  (:require
   [landing.agora.db :as db]
   [next.jdbc :as jdbc])
  (:import
   (java.security MessageDigest)))

(defn sha-256
  "SHA-256 hex digest of the UTF-8 bytes of `text`."
  [text]
  (let [bytes (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes ^String text "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn write-blob
  "Store `text`, addressed by its SHA-256 hash, and return the hash. Idempotent:
  re-writing identical content is a no-op (INSERT IGNORE on the hash key)."
  [text]
  (let [hash (sha-256 text)]
    (jdbc/execute! db/ds
                   ["INSERT IGNORE INTO AGORA_BLOB (hash, content) VALUES (?, ?)" hash text])
    hash))

(defn read-blob
  "Return the text stored under `hash`, or nil if no such blob exists."
  [hash]
  (:AGORA_BLOB/content
   (jdbc/execute-one! db/ds
                      ["SELECT content FROM AGORA_BLOB WHERE hash = ?" hash])))
