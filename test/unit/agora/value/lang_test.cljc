(ns agora.value.lang-test
  (:require
   [agora.value.lang :as sut]
   [clojure.test     :refer [deftest is testing]]))

(defn- broken [documents] (set (map :rule (mapcat #((:check %) documents) sut/rules))))

(deftest rules-test
  (is (empty? (broken [{:lang :fr
                        :content {:inputs [{:type :definition
                                            :name "rapide"
                                            :lang :en
                                            :major 1}
                                           {:id #uuid "00000000-0000-4000-8000-000000000001"}]}}])))
  (testing "a document in an unknown language"
    (is ((broken [{:lang :de
                   :content {:inputs []}}])
         :lang/known)))
  (testing "a citation in an unknown language"
    (is ((broken [{:lang :fr
                   :content {:inputs [{:type :definition
                                       :name "rapide"
                                       :lang :zz
                                       :major 1}]}}])
         :lang/known))))
