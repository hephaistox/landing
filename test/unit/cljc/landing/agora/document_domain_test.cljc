(ns landing.agora.document-domain-test
  "Pure domain rules — no database, no adapter. Runs in clj (and would in cljs)."
  (:require
   [clojure.test                  :refer [deftest is testing]]
   [landing.agora.document-domain :as sut]))

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

(deftest cite-refs
  (testing "extracts cited KIs from body tokens as ki-input TNLRs in the given lang"
    (is (= [{:type "ki"
             :name "confidence-is-partial"
             :lang "fr"
             :major 1}
            {:type "ki"
             :name "confidence-over-binary"
             :lang "fr"
             :major 2}]
           (sut/cite-refs
            "See [[ki:confidence-is-partial@1|partial]] then [[ki:confidence-over-binary@2]] here."
            "fr"))))
  (testing "dedupes repeated citations and tolerates no tokens"
    (is (= [{:type "ki"
             :name "a"
             :lang "en"
             :major 1}]
           (sut/cite-refs "[[ki:a@1]] and again [[ki:a@1]]" "en")))
    (is (= [] (sut/cite-refs "no citations here" "fr")))
    (is (= [] (sut/cite-refs nil "fr")))))

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
