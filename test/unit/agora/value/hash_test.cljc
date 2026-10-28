(ns agora.value.hash-test
  (:require
   [agora.value.hash :as sut]
   [clojure.test     :refer [deftest is testing]]))

(def version
  {:id #uuid "e7a1c3d5-2b4f-4a68-9c0e-1f3a5b7d9e2c"
   :title "Les ventes de 2030 — prévision"
   :text "ligne 1\nligne \"2\""
   :inputs [{:type :definition
             :name "x"
             :lang :fr
             :major 1}]
   :published-at #inst "2026-09-01T10:00:00Z"
   :minor 0})

(deftest sha-256-test
  (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" (sut/sha-256 ""))))

(deftest of-test
  (testing "the same hash in Clojure and ClojureScript"
    (is (= "f64e6cc9d0079b691d7c81740594be5b4aead8dbdc1d5e4ef027ad8fceb9de54" (sut/of version))))
  (testing "maps sorted by key"
    (is (= "{:a {:c 3, :d 2}, :b 1}"
           (sut/canonical {:b 1
                           :a {:d 2
                               :c 3}})))
    (is (= (sut/of {:b 1
                    :a 2})
           (sut/of {:a 2
                    :b 1}))))
  (testing "any change gives another hash"
    (is (not= (sut/of version) (sut/of (assoc version :title "Les ventes de 2030")))))
  (testing "a hash follows its grammar" (is (re-matches #"[0-9a-f]{64}" (sut/of version)))))
