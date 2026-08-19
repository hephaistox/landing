(ns landing.agora.admin-test
  (:require
   [clojure.test        :refer [deftest is testing]]
   [landing.agora.admin :as sut]))

(def ^:private people
  {"u1" "Anthony"
   "sun" "Sun Tzŭ"})

(defn- version
  "A published version of a lineage with no inputs, its derived blob as given."
  [id owner extra computed]
  (merge {:id id
          :type :ki
          :name id
          :lang :fr
          :major 1
          :minor 0
          :draft false
          :publication-id nil
          :content (merge {:owner-id owner
                           :inputs []}
                          extra)
          :computed computed}))

(defn- computed-fixes
  [versions]
  (:computed-fixes (sut/discrepancies {:versions versions
                                       :edges #{}
                                       :people people})))

(deftest byline-is-the-display-name-of-the-attributed-person
  (testing "a version whose cached name matches its owner's is left alone"
    (is (empty? (computed-fixes [(version "a"
                                          "u1"
                                          nil
                                          {:pins []
                                           :author "Anthony"})]))))
  (testing "a stale cached name is repaired from AGORA_USER"
    (is (= [{:id "a"
             :computed {:pins []
                        :author "Anthony"}}]
           (computed-fixes [(version "a"
                                     "u1"
                                     nil
                                     {:pins []
                                      :author "Antoine"})]))))
  (testing "a work's byline is its cited author, not its owner"
    (is (= [{:id "w"
             :computed {:pins []
                        :author "Sun Tzŭ"}}]
           (computed-fixes [(version "w" "u1" {:author-id "sun"} {:pins []})]))))
  (testing "an unknown person leaves no name at all"
    (is (= [{:id "a"
             :computed {:pins []}}]
           (computed-fixes [(version "a"
                                     "gone"
                                     nil
                                     {:pins []
                                      :author "Anthony"})])))))

(deftest a-name-in-the-immutable-content-is-ignored
  (testing "the byline comes from the derived blob only — a leftover copy never shows"
    (is (= [{:id "a"
             :computed {:pins []
                        :author "Anthony"}}]
           (computed-fixes [(version "a" "u1" {:author "Antoine"} {:pins []})])))))
