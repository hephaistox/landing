(ns agora.entity.person-test
  (:require
   [agora.entity.person :as sut]
   [clojure.test        :refer [deftest is testing]]))

(defn- broken [persons] (set (map :rule (mapcat #((:check %) persons) sut/rules))))

(deftest person?-test
  (is (sut/person? {:id #uuid "00000000-0000-4000-8000-000000000001"
                    :name "Victor hugo"}))
  (is (not (sut/person? {:id #uuid "00000000-0000-4000-8000-000000000001"})))
  (is (not (sut/person? {:id #uuid "00000000-0000-4000-8000-000000000001"
                         :name "Victor hugo"
                         :email "v@h.fr"})))
  (is (not (sut/person? {:id 1
                         :name "Victor hugo"}))))

(deftest rules-test
  (is (empty? (broken [{:id #uuid "00000000-0000-4000-8000-000000000001"
                        :name "Agora"}
                       {:id #uuid "00000000-0000-4000-8000-000000000002"
                        :name "Victor hugo"}])))
  (testing "a person without a name"
    (is ((broken [{:id #uuid "00000000-0000-4000-8000-000000000001"}]) :person/shape)))
  (testing "an id for two persons"
    (is ((broken [{:id #uuid "00000000-0000-4000-8000-000000000001"
                   :name "Agora"}
                  {:id #uuid "00000000-0000-4000-8000-000000000001"
                   :name "Victor hugo"}])
         :person/unique-id))))

(deftest validate-test
  (is (nil? (sut/validate {:id #uuid "00000000-0000-4000-8000-000000000001"
                           :name "Victor hugo"})))
  (testing "a structured error: the humanized error, the schema and the data"
    (let [error (sut/validate {:id #uuid "00000000-0000-4000-8000-000000000001"
                               :name ""})]
      (is (= #{:error :schema :data} (set (keys error))))
      (is (contains? (:error error) :name))))
  (testing "an unexpected key"
    (is (contains? (:error (sut/validate {:id #uuid "00000000-0000-4000-8000-000000000001"
                                          :name "V"
                                          :email "v@h.fr"}))
                   :email))))
