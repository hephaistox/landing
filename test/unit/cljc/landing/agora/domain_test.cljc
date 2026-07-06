(ns landing.agora.domain-test
  "Pure domain rules — no database, no adapter. Runs in clj (and would in cljs)."
  (:require
   [clojure.test         :refer [deftest is testing]]
   [landing.agora.domain :as sut]))

(def t-a
  {:type "ki"
   :name "a"
   :lang "fr"
   :major 1})
(def t-b
  {:type "ki"
   :name "b"
   :lang "fr"
   :major 1})

(deftest kind-data
  (is (= (count sut/kinds) (count (distinct sut/kind-ids))) "kind ids are unique")
  (is (every? sut/kind-color (map name sut/kind-ids)) "every kind has an accent colour")
  (is (= #{:derived :verifiable :foundation} (set (vals sut/kind-family)))))

(deftest tnlr
  (is (= ["ki" "a" "fr" 1] (sut/tnlr-key t-a)))
  (is (= t-a (sut/tnlr (assoc t-a :minor 3 :id "x"))) "strips non-identity fields")
  (is (sut/same-tnlr? t-a (assoc t-a :minor 9 :id "z")) "identity ignores minor/id")
  (is (not (sut/same-tnlr? t-a t-b))))

(deftest declarations
  (testing "add-declared dedups by TNLR and keeps only the TNLR"
    (is (= [t-b t-a]
           (-> []
               (sut/add-declared (assoc t-a :id "x"))
               (sut/add-declared t-b)
               (sut/add-declared t-a)))))
  (testing "drop-declared removes by TNLR"
    (is (= [t-b]
           (-> []
               (sut/add-declared t-a)
               (sut/add-declared t-b)
               (sut/drop-declared t-a))))))

(deftest pins
  (testing "pin-all resolves every declaration to its latest id, keyed by tnlr-key"
    (is (= {(sut/tnlr-key t-a) "a-latest"
            (sut/tnlr-key t-b) "b-latest"}
           (sut/pin-all [t-a t-b] #(str (:name %) "-latest")))))
  (testing "repin updates only the matching TNLR's pin"
    (is (= {(sut/tnlr-key t-a) "a2"
            (sut/tnlr-key t-b) "b1"}
           (sut/repin {(sut/tnlr-key t-a) "a1"
                       (sut/tnlr-key t-b) "b1"}
                      t-a
                      "a2")))))

(deftest refs-and-successors
  (testing "input-refs zip declarations with their pins (nil id when unpinned)"
    (is (= [(assoc t-a :id "a1") (assoc t-b :id nil)]
           (sut/input-refs [t-a t-b] {(sut/tnlr-key t-a) "a1"}))))
  (testing "successor-tuples: one row per declaration"
    (is (= [{:tnlr t-a
             :successor-id "s"}
            {:tnlr t-b
             :successor-id "s"}]
           (sut/successor-tuples "s" [t-a t-b])))))
