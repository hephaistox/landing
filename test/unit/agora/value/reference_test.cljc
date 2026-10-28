(ns agora.value.reference-test
  (:require
   [agora.value.reference :as sut]
   [auto-core.schema      :as schema]
   [clojure.test          :refer [deftest is testing]]))

(defn- valid? [grammar r] (nil? (schema/validate-data-humanize grammar r)))

(deftest schemas-test
  (doseq [grammar [sut/person-schema
                   sut/user-schema
                   sut/publication-schema
                   sut/objection-schema
                   sut/version-schema]]
    (is (valid? grammar {:id #uuid "00000000-0000-4000-8000-000000000001"}))
    (testing "a reference copies nothing"
      (is (not (valid? grammar
                       {:id #uuid "00000000-0000-4000-8000-000000000001"
                        :name "Agora"}))))
    (testing "an id is a uuid" (is (not (valid? grammar {:id "agora"}))))))
