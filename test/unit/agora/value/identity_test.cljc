(ns agora.value.identity-test
  (:require
   [agora.value.identity :as sut]
   [clojure.test         :refer [deftest is testing]]))

(defn- rapide
  [id major minor]
  {:id id
   :type :definition
   :name "rapide"
   :lang :fr
   :major major
   :minor minor})

(defn- broken [documents] (set (map :rule (mapcat #((:check %) documents) sut/rules))))

(deftest tnlr-test
  (is (= {:type :definition
          :name "rapide"
          :lang :fr
          :major 1}
         (sut/tnlr (assoc (rapide #uuid "00000000-0000-4000-8000-000000000001" 1 3) :foo :bar))))
  (is (= [:definition "rapide" :fr 1 3]
         (sut/identity-of (rapide #uuid "00000000-0000-4000-8000-000000000001" 1 3)))))

(deftest next-minor-test
  (testing "nothing published yet"
    (is (= 0 (sut/next-minor [] (rapide #uuid "00000000-0000-4000-8000-000000000009" 1 nil)))))
  (testing "after the latest minor of the same major"
    (is (= 2
           (sut/next-minor [(rapide #uuid "00000000-0000-4000-8000-000000000001" 1 0)
                            (rapide #uuid "00000000-0000-4000-8000-000000000002" 1 1)
                            (rapide #uuid "00000000-0000-4000-8000-000000000003" 2 0)]
                           (rapide #uuid "00000000-0000-4000-8000-000000000009" 1 nil)))))
  (testing "another language is another lineage"
    (is (= 0
           (sut/next-minor
            [(assoc (rapide #uuid "00000000-0000-4000-8000-000000000001" 1 0) :lang :en)]
            (rapide #uuid "00000000-0000-4000-8000-000000000009" 1 nil))))))

(deftest latest-minor-test
  (let [documents
        [(rapide #uuid "00000000-0000-4000-8000-000000000001" 1 0)
         (rapide #uuid "00000000-0000-4000-8000-000000000003" 1 2)
         (rapide #uuid "00000000-0000-4000-8000-000000000002" 1 1)
         (rapide #uuid "00000000-0000-4000-8000-000000000004" 1 nil)
         (rapide #uuid "00000000-0000-4000-8000-000000000005" 2 0)
         (assoc (rapide #uuid "00000000-0000-4000-8000-000000000006" 1 5) :lang :en)
         (assoc (rapide #uuid "00000000-0000-4000-8000-000000000007" 1 7) :name "chrono")
         (assoc (rapide #uuid "00000000-0000-4000-8000-000000000008" 1 9) :type :illustration)]
        citation {:type :definition
                  :name "rapide"
                  :lang :fr
                  :major 1}]
    (is (= #uuid "00000000-0000-4000-8000-000000000003" (:id (sut/latest-minor documents citation)))
        "the highest published minor, among documents of other names, languages, majors and types")
    (is (= #uuid "00000000-0000-4000-8000-000000000005"
           (:id (sut/latest-minor documents (assoc citation :major 2)))))
    (testing "nothing published for that major"
      (is (nil? (sut/latest-minor documents (assoc citation :major 3)))))
    (testing "only a draft"
      (is (nil? (sut/latest-minor [(rapide #uuid "00000000-0000-4000-8000-000000000004" 1 nil)]
                                  citation))))))

(deftest rules-test
  (is (empty? (broken [(rapide #uuid "00000000-0000-4000-8000-000000000001" 1 0)
                       (rapide #uuid "00000000-0000-4000-8000-000000000002" 1 1)
                       (rapide #uuid "00000000-0000-4000-8000-000000000003" 1 nil)
                       (rapide #uuid "00000000-0000-4000-8000-000000000004" 1 nil)])))
  (testing "two published versions with one identity"
    (is ((broken [(rapide #uuid "00000000-0000-4000-8000-000000000001" 1 0)
                  (rapide #uuid "00000000-0000-4000-8000-000000000002" 1 0)])
         :identity/unique)))
  (testing "one id for two versions"
    (is ((broken [(rapide #uuid "00000000-0000-4000-8000-000000000001" 1 0)
                  (rapide #uuid "00000000-0000-4000-8000-000000000001" 1 1)])
         :identity/unique)))
  (testing "a gap in the minors"
    (is ((broken [(rapide #uuid "00000000-0000-4000-8000-000000000001" 1 0)
                  (rapide #uuid "00000000-0000-4000-8000-000000000002" 1 2)])
         :identity/minors))))

(deftest validate-test
  (let [tnlr {:type :definition
              :name "rapide"
              :lang :fr
              :major 1}]
    (is (nil? (sut/validate-tnlr tnlr)))
    (is (nil? (sut/validate (assoc tnlr :minor 3))))
    (is (nil? (sut/validate tnlr)) "a draft has no minor")
    (is (some? (sut/validate (assoc tnlr :minor nil))) "not even a nil one")
    (testing "each part follows its own grammar"
      (is (contains? (:error (sut/validate-tnlr (assoc tnlr :type :oeuvre))) :type))
      (is (contains? (:error (sut/validate-tnlr (assoc tnlr :name ""))) :name))
      (is (contains? (:error (sut/validate-tnlr (assoc tnlr :lang :de))) :lang))
      (is (contains? (:error (sut/validate-tnlr (assoc tnlr :major 0))) :major))
      (is (contains? (:error (sut/validate (assoc tnlr :minor -1))) :minor)))
    (testing "a tnlr has no minor"
      (is (contains? (:error (sut/validate-tnlr (assoc tnlr :minor 0))) :minor)))))
