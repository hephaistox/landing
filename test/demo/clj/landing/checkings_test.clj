(ns landing.checkings-test
  (:require
   [clojure.test      :refer [deftest is]]
   [landing.checkings :as sut]))

(defn- all-test-valids?
  [{:keys [tests]}]
  (every? #(= :valid %)
          (-> tests
              vals)))

(deftest checking-during-test (is (empty? (remove all-test-valids? (sut/validate-images)))))

(deftest validate-dics-test (is (empty? (remove all-test-valids? sut/validate-dics))))

(deftest links-test (is (empty? (remove all-test-valids? (sut/validate-links)))))
