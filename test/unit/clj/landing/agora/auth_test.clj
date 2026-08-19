(ns landing.agora.auth-test
  (:require
   [clojure.string                   :as str]
   [clojure.test                     :refer [deftest is testing]]
   [landing.agora.auth               :as sut]
   [landing.agora.person.alias-words :as w])
  (:import (java.sql SQLIntegrityConstraintViolationException)))

(def ^:private insert-with-alias! #'sut/insert-with-alias!)

(defn- violation
  "The exception MySQL raises for `key-name`, as the driver words it."
  [key-name]
  (SQLIntegrityConstraintViolationException. (str "Duplicate entry 'x' for key '" key-name "'")))

(defn- failing-insert
  "An `insert!` that raises `key-name`'s uniqueness violation for its first `n` calls, then returns
  the alias it was given. `calls` counts the attempts."
  [n key-name calls]
  (fn [display-name _alias-key]
    (swap! calls inc)
    (if (<= @calls n) (throw (violation key-name)) display-name)))

(deftest a-taken-alias-is-redrawn
  (testing "the insert is retried with a fresh alias until one is free"
    (let [calls (atom 0)
          result (insert-with-alias! "fr" (failing-insert 2 "uq_user_alias" calls))]
      (is (= 3 @calls))
      (is (not (str/blank? result)))))
  (testing "past the redraws, the alias takes a numeric suffix rather than looping forever"
    (let [calls (atom 0)
          result (insert-with-alias! "fr" (failing-insert 6 "uq_user_alias" calls))]
      (is (= 7 @calls))
      (is (re-find #"\d+$" result)))))

(deftest another-uniqueness-violation-is-not-mistaken-for-a-taken-alias
  (testing "a duplicate email reaches the caller — redrawing an alias would never free it"
    (let [calls (atom 0)]
      (is (thrown? SQLIntegrityConstraintViolationException
                   (insert-with-alias! "fr" (failing-insert 1 "uq_user_email" calls))))
      (is (= 1 @calls) "and it is not retried"))))

(deftest the-alias-is-drawn-in-the-requested-language
  (let [drawn (fn [lang]
                (str/split (insert-with-alias! lang (fn [display-name _] display-name)) #" "))
        nouns-of (fn [lang]
                   (into #{} (map (comp str/capitalize first)) (get-in w/words [lang :nouns])))]
    (testing "the noun comes from that language's vocabulary — last in English, first in French"
      (is (contains? (nouns-of :en) (second (drawn "en"))))
      (is (contains? (nouns-of :fr) (first (drawn "fr")))))))
