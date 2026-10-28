(ns agora.value.version-test
  (:require
   [agora.value.version :as sut]
   [clojure.test        :refer [deftest is testing]]))

(defn- broken [documents] (set (map :rule (mapcat #((:check %) documents) sut/rules))))

(deftest draft?-test (is (sut/draft? {})) (is (sut/published? {:minor 0})))

(deftest rules-test
  (is (empty? (broken [{:name "rapide"
                        :minor 0}
                       {:name "groupe"}])))
  (testing "a nil minor"
    (is ((broken [{:name "rapide"
                   :minor nil}])
         :version/minor)))
  (testing "a negative minor"
    (is ((broken [{:name "rapide"
                   :minor -1}])
         :version/minor)))
  (testing "a minor that is not a number"
    (is ((broken [{:name "rapide"
                   :minor "1"}])
         :version/minor)))
  (testing "a type described by a draft"
    (is ((broken [{:name "type-work"}]) :version/described-types-published))))
