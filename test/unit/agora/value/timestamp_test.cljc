(ns agora.value.timestamp-test
  (:require
   [agora.value.timestamp :as sut]
   [clojure.test          :refer [deftest is testing]]))

(def prediction
  {:id #uuid "00000000-0000-4000-8000-000000000002"
   :type :prediction
   :name "ventes-2030"
   :lang :fr
   :major 1
   :minor 0
   :owner {:id #uuid "00000000-0000-4000-8000-000000000001"}
   :content {:title "Les ventes de 2030"
             :text "la moitié des voitures neuves seront électriques."
             :inputs []
             :published-at #inst "2026-09-01T10:00:00Z"}})

(def timestamped
  (assoc prediction
         :timestamp
         {:hash (sut/hash-of prediction)
          :requested-at #inst "2026-10-27T09:00:00Z"
          :status :pending}))

(defn- broken [documents] (set (map :rule (mapcat #((:check %) documents) sut/rules))))

(deftest hash-of-test
  (testing "the identity, the id of the owner and the content"
    (is (not= (sut/hash-of prediction)
              (sut/hash-of (assoc-in prediction [:content :text] "autre"))))
    (is (not= (sut/hash-of prediction) (sut/hash-of (assoc prediction :minor 1))))
    (is (not= (sut/hash-of prediction)
              (sut/hash-of
               (assoc prediction :owner {:id #uuid "00000000-0000-4000-8000-000000000009"})))))
  (testing "not the timestamp itself" (is (= (sut/hash-of prediction) (sut/hash-of timestamped)))))

(deftest rules-test
  (is (empty? (broken [prediction timestamped])))
  (testing "a version changed after it was timestamped"
    (is ((broken [(assoc-in timestamped [:content :text] "autre")]) :timestamp/hash)))
  (testing "a draft" (is ((broken [(dissoc timestamped :minor)]) :timestamp/version)))
  (testing "a type that may not be timestamped"
    (is ((broken [(assoc timestamped :type :article)]) :timestamp/version))))

(deftest advances?-test
  (is (sut/advances? :pending :incomplete))
  (is (sut/advances? :pending :complete))
  (is (not (sut/advances? :complete :incomplete)))
  (is (not (sut/advances? :incomplete :incomplete))))
