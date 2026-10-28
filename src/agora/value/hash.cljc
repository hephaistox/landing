(ns agora.value.hash
  "Hash.

## Definition

The **hash** of a value is the SHA-256 of its canonical text, in lower-case hexadecimal: 64
characters. The canonical text is the EDN of the value, its maps sorted by key. The same
value gives the same hash, in Clojure as in ClojureScript; any change gives another one.

## Goals

- Tell whether a content is exactly the one hashed earlier.
- Give a short, fixed-size fingerprint to timestamp a content.

## Examples

```clojure
(of {:b 1 :a 2}) ; same as (of {:a 2 :b 1})
```

## Links

- `agora.entity.timestamp`: a timestamp holds the hash of a published version."
  (:require
   [clojure.walk :as walk]
   #?(:cljs [goog.crypt :as crypt])
   #?(:cljs [goog.crypt.Sha256]))
  #?(:clj (:import [java.security MessageDigest])))

;; ---------------------------------------------------------------------------
;; Domain
;; ---------------------------------------------------------------------------

(def schema "The grammar of a hash: 64 lower-case hexadecimal characters." [:re #"^[0-9a-f]{64}$"])

(defn canonical
  "The canonical text of `x`: its EDN, its maps sorted by key."
  [x]
  (binding [*print-length* nil
            *print-level* nil]
    (pr-str (walk/postwalk #(if (map? %) (into (sorted-map) %) %) x))))

(defn sha-256
  "The SHA-256 of the UTF-8 bytes of the string `s`, in lower-case hexadecimal."
  [s]
  #?(:clj (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                                (.getBytes ^String s "UTF-8"))]
            (apply str (map #(format "%02x" (bit-and % 0xff)) digest)))
     :cljs (let [h (goog.crypt.Sha256.)]
             (.update h (crypt/stringToUtf8ByteArray s))
             (crypt/byteArrayToHex (.digest h)))))

(defn of "The hash of `x`." [x] (sha-256 (canonical x)))
