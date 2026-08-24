(ns landing.agora.admin-test
  (:require
   [clojure.test        :refer [deftest is testing]]
   [landing.agora.admin :as sut]))

(def ^:private people
  {"u1" "Anthony"
   "sun" "Sun Tzŭ"})

(defn- version
  "A published version of a lineage with no inputs, its derived blob as given. `owner` and the
  credited `author-id` are columns on the version, not fields of `content`; `content-extra` is merged
  into the immutable blob."
  ([id owner computed] (version id owner nil nil computed))
  ([id owner author-id content-extra computed]
   {:id id
    :type :ki
    :name id
    :lang :fr
    :major 1
    :minor 0
    :draft false
    :publication-id nil
    :owner-id owner
    :author-id author-id
    :content (merge {:inputs []} content-extra)
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
                                          {:pins []
                                           :author "Anthony"})]))))
  (testing "a stale cached name is repaired from AGORA_USER"
    (is (= [{:id "a"
             :computed {:pins []
                        :author "Anthony"}}]
           (computed-fixes [(version "a"
                                     "u1"
                                     {:pins []
                                      :author "Antoine"})]))))
  (testing "a work's byline is its cited author, not its owner"
    (is (= [{:id "w"
             :computed {:pins []
                        :author "Sun Tzŭ"}}]
           (computed-fixes [(version "w" "u1" "sun" nil {:pins []})]))))
  (testing "an unknown person leaves no name at all"
    (is (= [{:id "a"
             :computed {:pins []}}]
           (computed-fixes [(version "a"
                                     "gone"
                                     {:pins []
                                      :author "Anthony"})])))))

(deftest a-name-in-the-immutable-content-is-ignored
  (testing "the byline comes from the derived blob only — a leftover copy never shows"
    (is (= [{:id "a"
             :computed {:pins []
                        :author "Anthony"}}]
           (computed-fixes [(version "a" "u1" nil {:author "Antoine"} {:pins []})])))))
