(ns agora.repository-test
  (:require
   [agora.repository :as sut]
   [clojure.test     :refer [deftest is testing]]))

(def anthony
  {:id #uuid "00000000-0000-4000-8000-000000000001"
   :name "Anthony"})

(def account
  {:id #uuid "00000000-0000-4000-8000-000000000001"
   :provider :google
   :provider-id "google-a"})

(deftest apply-changes-test
  (is (= sut/empty-repository (sut/apply-changes sut/empty-repository {})))
  (testing "a created person"
    (is (= (assoc sut/empty-repository :persons [anthony])
           (sut/apply-changes sut/empty-repository {:created {:persons [anthony]}}))))
  (testing "a changed person replaces the one with its :id"
    (is (= [(assoc anthony :name "Antoine")]
           (:persons (sut/apply-changes (assoc sut/empty-repository :persons [anthony])
                                        {:changed {:persons [(assoc anthony :name "Antoine")]}})))))
  (testing "both"
    (is (= (assoc sut/empty-repository :persons [(assoc anthony :name "Antoine")] :users [account])
           (sut/apply-changes (assoc sut/empty-repository :persons [anthony])
                              {:created {:users [account]}
                               :changed {:persons [(assoc anthony :name "Antoine")]}}))))
  (testing "a missing kind starts empty"
    (is (= {:persons [anthony]} (sut/apply-changes {} {:created {:persons [anthony]}})))))

(deftest apply-changes-refused-test
  (let [thrown (fn [repository changes]
                 (try (sut/apply-changes repository changes)
                      nil
                      (catch #?(:clj Exception
                                :cljs :default)
                        e
                        (ex-message e))))]
    (testing "an unknown kind"
      (is (= "Invalid changes" (thrown sut/empty-repository {:created {:foo [anthony]}}))))
    (testing "an entity without a uuid"
      (is (= "Invalid changes" (thrown sut/empty-repository {:created {:persons [{:name "A"}]}}))))
    (testing "a created :id already there"
      (is (= "Created ids already there"
             (thrown (assoc sut/empty-repository :persons [anthony])
                     {:created {:persons [anthony]}}))))
    (testing "a changed :id missing"
      (is (= "Changed ids missing"
             (thrown sut/empty-repository {:changed {:persons [anthony]}}))))))

(deftest rules-test
  (let [broken #(set (map :rule (mapcat (fn [r] ((:check r) %)) sut/rules)))]
    (is (empty? (broken sut/empty-repository)))
    (testing "an unknown key"
      (is ((broken (assoc sut/empty-repository :foo :bar)) :repository/shape)))
    (testing "a kind that is not a vector"
      (is ((broken (assoc sut/empty-repository :persons :bar)) :repository/shape)))))
