(ns landing.agora.altcha-test
  (:require
   [clojure.test         :refer [deftest is testing]]
   [landing.agora.altcha :as sut]))

(def ^:private dev-key @#'sut/dev-hmac-key)
(defn- validate [secret env] (#'sut/validate-key secret env))

(deftest hmac-key-production-guard-test
  (testing "production refuses every key that would let a signature be forged"
    (is (thrown? clojure.lang.ExceptionInfo (validate nil :prod)) "unset")
    (is (thrown? clojure.lang.ExceptionInfo (validate "" :prod)) "blank")
    (is (thrown? clojure.lang.ExceptionInfo (validate "   " :prod)) "whitespace only")
    (is (thrown? clojure.lang.ExceptionInfo (validate "short" :prod)) "below the minimum length")
    (is (thrown? clojure.lang.ExceptionInfo (validate dev-key :prod))
        "the development key — it is in the source, so publishing it hands out valid captchas"))
  (testing "production accepts a real key"
    (let [key (apply str (repeat 64 "a"))] (is (= key (validate key :prod)))))
  (testing "outside production the development key is the fallback, and never throws"
    (is (= dev-key (validate nil :dev)))
    (is (= dev-key (validate "" :dev)))
    (is (= "chosen-in-dev" (validate "chosen-in-dev" :dev)))))

(deftest challenge-is-signed-test
  (testing "a fresh challenge carries the algorithm, a salt with an expiry, and a signature"
    (let [{:keys [algorithm challenge salt signature maxnumber]} (sut/challenge)]
      (is (= "SHA-256" algorithm))
      (is (re-find #"\?expires=\d+" salt))
      (is (= 64 (count challenge)) "SHA-256, hex")
      (is (= 64 (count signature)) "HMAC-SHA256, hex")
      (is (pos-int? maxnumber)))))

(deftest verify-rejects-junk-test
  (testing "a payload we never issued is refused, whatever shape it takes"
    (is (false? (sut/verify nil)))
    (is (false? (sut/verify "")))
    (is (false? (sut/verify "not-base64")))
    (is (false? (sut/verify (.encodeToString (java.util.Base64/getEncoder)
                                             (.getBytes "{\"algorithm\":\"SHA-256\"}" "UTF-8"))))
        "well-formed JSON without a valid signature")))
