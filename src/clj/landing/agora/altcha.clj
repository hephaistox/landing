(ns landing.agora.altcha
  "Self-hosted ALTCHA proof-of-work captcha (https://altcha.org), used to gate account
  creation to humans without a third-party service or tracking.

  The server issues a signed challenge and later verifies the solved payload. A valid
  payload proves: (1) it solves one of *our* challenges — the HMAC signature over the
  challenge, keyed by `ALTCHA_HMAC_KEY`, can't be forged; (2) it isn't expired — an
  `expires` stamp is embedded in the salt; (3) the client actually did the work — the
  solution number hashes to the challenge. Solved challenges are single-use within the
  expiry window (in-memory), so a solution can't be replayed."
  (:require
   [cheshire.core  :as json]
   [clojure.string :as str])
  (:import (java.security MessageDigest SecureRandom)
           (java.util Base64)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(def ^:private algorithm "SHA-256")
(def ^:private max-number 100000)
(def ^:private ttl-seconds 300)
(def ^:private rng (SecureRandom.))

(defn- hmac-key [] (or (System/getenv "ALTCHA_HMAC_KEY") "agora-dev-altcha-secret"))

(defn- hex [^bytes bs] (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn- sha256-hex
  [^String s]
  (hex (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))))

(defn- hmac-hex
  [^String key-str ^String msg]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (.getBytes key-str "UTF-8") "HmacSHA256"))
    (hex (.doFinal mac (.getBytes msg "UTF-8")))))

(defn- random-hex
  [n]
  (let [b (byte-array n)]
    (.nextBytes rng b)
    (hex b)))

(defn- now-seconds [] (quot (System/currentTimeMillis) 1000))

(defn challenge
  "A fresh ALTCHA challenge for the widget: {:algorithm :challenge :salt :signature
  :maxnumber}. The salt carries an `expires` stamp; the signature is our HMAC over the
  challenge, so only challenges we issued verify."
  []
  (let [salt (str (random-hex 12) "?expires=" (+ (now-seconds) ttl-seconds))
        number (.nextInt rng max-number)
        chal (sha256-hex (str salt number))]
    {:algorithm algorithm
     :challenge chal
     :salt salt
     :signature (hmac-hex (hmac-key) chal)
     :maxnumber max-number}))

;; Solved-challenge → expiry epoch, so a solution is single-use. Pruned lazily on use.
(defonce ^:private consumed (atom {}))

(defn- salt-expires
  [salt]
  (some-> (re-find #"expires=(\d+)" (str salt))
          second
          parse-long))

(defn- consume!
  "Record `chal` as used until `exp`; returns true the first time, false on replay.
  Prunes expired entries."
  [chal exp now]
  (let [[old] (swap-vals! consumed
                          (fn [c]
                            (let [live (into {} (remove (fn [[_ e]] (< e now)) c))]
                              (cond-> live
                                (not (contains? live chal)) (assoc chal exp)))))]
    (not (contains? old chal))))

(defn- constant=
  [a b]
  (MessageDigest/isEqual (.getBytes (str a) "UTF-8") (.getBytes (str b) "UTF-8")))

(defn verify
  "True when `payload` (the base64-JSON the widget submits) is a valid, unexpired,
  not-yet-used solution to one of our challenges. Any decode/verify error → false."
  [payload]
  (boolean (when (and (string? payload) (not (str/blank? payload)))
             (try (let [m (json/parse-string (String. (.decode (Base64/getDecoder) ^String payload)
                                                      "UTF-8")
                                             true)
                        {:keys [algorithm challenge number salt signature]} m
                        now (now-seconds)
                        exp (salt-expires salt)]
                    (and (= algorithm "SHA-256")
                         exp
                         (<= now exp)
                         (constant= challenge (sha256-hex (str salt number)))
                         (constant= signature (hmac-hex (hmac-key) challenge))
                         (consume! challenge exp now)))
                  (catch Exception _ false)))))
