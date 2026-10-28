(ns agora.entity.document-test
  (:require
   [agora.entity.document :as sut]
   [clojure.test          :refer [deftest is testing]]))

(def rapide
  {:id #uuid "00000000-0000-4000-8000-000000000002"
   :type :definition
   :name "rapide"
   :lang :fr
   :major 1
   :minor 0
   :owner {:id #uuid "00000000-0000-4000-8000-000000000001"}
   :content {:title "Rapide"
             :text "atteindre 100 km/h en moins de 5 secondes."
             :inputs []
             :published-at #inst "2026-08-28T12:39:01Z"}})

(defn- broken [documents] (set (map :rule (mapcat #((:check %) documents) sut/rules))))

(deftest validate-test
  (is (nil? (sut/validate rapide)))
  (is (nil? (sut/validate (dissoc rapide :minor))) "a draft")
  (is (some? (sut/validate (assoc rapide :minor nil))) "a draft has no :minor key")
  (is (nil? (sut/validate (-> rapide
                              (assoc :type :work
                                     :author {:id #uuid "00000000-0000-4000-8000-000000000003"})
                              (update :content assoc :text nil :year 1862)))))
  (is (nil? (sut/validate (assoc-in rapide
                           [:content :inputs]
                           [{:id #uuid "00000000-0000-4000-8000-000000000004"}
                            {:type :work
                             :name "les-miserables"
                             :lang :fr
                             :major 1}]))))
  (testing "the identity follows its grammar"
    (is (contains? (:error (sut/validate (assoc rapide :lang :de))) :lang)))
  (testing "a citation follows its grammar"
    (is (contains? (:error (sut/validate (assoc-in rapide
                                          [:content :inputs]
                                          [{:type :work
                                            :name #uuid "00000000-0000-4000-8000-000000000004"
                                            :lang :fr}])))
                   :content)))
  (testing "an owner is designated by its :id only"
    (is (contains? (:error (sut/validate (assoc rapide
                                                :owner
                                                {:id #uuid "00000000-0000-4000-8000-000000000001"
                                                 :name "Agora"})))
                   :owner)))
  (testing "a timestamp with a proof exactly when not pending"
    (let [t {:hash "04ee5cc520f6abaa353bd042dbba0635783f9c595706137258688d8b2280f830"
             :requested-at #inst "2026-10-27T09:00:00Z"
             :status :pending}]
      (is (nil? (sut/validate (assoc rapide :timestamp t))))
      (is (some? (sut/validate (assoc rapide :timestamp (assoc t :proof "cHJvb2Y=")))))
      (is (some? (sut/validate (assoc rapide :timestamp (assoc t :status :complete)))))))
  (testing "a blank title"
    (is (contains? (:error (sut/validate (assoc-in rapide [:content :title] ""))) :content)))
  (testing "a date that is not an instant"
    (is (contains? (:error (sut/validate (assoc-in rapide [:content :published-at] "hier")))
                   :content)))
  (testing "an unexpected key"
    (is (contains? (:error (sut/validate (assoc rapide :kind :definition))) :kind))))

(deftest rules-test
  (is (empty? (broken [rapide])))
  (is ((broken [(update rapide :content dissoc :published-at)]) :document/shape)))
