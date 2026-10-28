(ns agora.value.citation-test
  (:require
   [agora.value.citation :as sut]
   [agora.value.identity :as identity]
   [clojure.test         :refer [deftest is testing]]))

(def rapide-ref
  {:type :definition
   :name "rapide"
   :lang :fr
   :major 1})

(defn- broken [documents] (set (map :rule (mapcat #((:check %) documents) sut/rules))))

(deftest parse-target-test
  (is (= rapide-ref (sut/parse-target "definition:rapide:fr@1")))
  (is (= {:id #uuid "a526a25d-c823-4295-a8b4-0e6514d3768a"}
         (sut/parse-target "id:a526a25d-c823-4295-a8b4-0e6514d3768a")))
  (testing "a link without its language" (is (nil? (sut/parse-target "work:les-miserables@1")))))

(deftest links-test
  (is (= [{:target "definition:rapide:fr@1"
           :citation rapide-ref
           :label "définition"}]
         (sut/links "Par « rapide », j'entends … ([[definition:rapide:fr@1|définition]]).")))
  (is (empty? (sut/links nil))))

(deftest render-link-test
  (is (= "[[definition:rapide:fr@1|définition]]" (sut/render-link rapide-ref "définition")))
  (is (= "[[id:00000000-0000-4000-8000-000000000005]]"
         (sut/render-link {:id #uuid "00000000-0000-4000-8000-000000000005"} nil))))

(deftest replace-links-test
  (is (= "a [[definition:rapide:fr@1|b]] c [[work:x@1]]"
         (sut/replace-links "a [[id:a526a25d-c823-4295-a8b4-0e6514d3768a|b]] c [[work:x@1]]"
                            (fn [_] rapide-ref)))
      "the label is kept, an incomplete link is left as it is")
  (is (nil? (sut/replace-links nil identity))))

(deftest follow-a-citation-test
  (let [documents [(assoc rapide-ref :id #uuid "00000000-0000-4000-8000-000000000002" :minor 0)
                   (assoc rapide-ref :id #uuid "00000000-0000-4000-8000-000000000003" :minor 1)
                   (assoc rapide-ref :id #uuid "00000000-0000-4000-8000-000000000004")]
        text "Par « rapide », j'entends … ([[definition:rapide:fr@1|définition]])."]
    (is (= [#uuid "00000000-0000-4000-8000-000000000003"]
           (map #(:id (identity/latest-minor documents (:citation %))) (sut/links text)))
        "a link leads to the latest published minor of its major")))

(deftest rules-test
  (let [rapide (assoc rapide-ref
                      :id #uuid "00000000-0000-4000-8000-000000000001"
                      :minor 0
                      :content {:published-at #inst "2026-08-28T12:39:01Z"})
        article {:type :article
                 :minor 1
                 :content {:published-at #inst "2026-08-29T00:00:00Z"
                           :text "[[definition:rapide:fr@1|définition]]"
                           :inputs [rapide-ref]}}]
    (is (empty? (broken [rapide article])))
    (testing "a citation with a minor"
      (is ((broken [rapide (update-in article [:content :inputs] conj (assoc rapide-ref :minor 0))])
           :citation/shape)))
    (testing "a tnlr in an unknown language"
      (is ((broken [rapide
                    (update-in article [:content :inputs] conj (assoc rapide-ref :lang :de))])
           :citation/shape)))
    (testing "a link that is not a citation"
      (is ((broken [rapide (assoc-in article [:content :inputs] [])]) :citation/links)))
    (testing "a citation of what was published later"
      (is ((broken [rapide
                    (assoc-in article [:content :published-at] #inst "2026-01-01T00:00:00Z")])
           :citation/published-before)))
    (testing "an extract citing no work"
      (is ((broken [rapide {:type :extract
                            :minor 0
                            :content {:inputs [rapide-ref]}}])
           :citation/inputs)))
    (testing "a work citing something"
      (is ((broken [rapide {:type :work
                            :minor 0
                            :content {:inputs [rapide-ref]}}])
           :citation/inputs)))
    (testing "a deduction citing nothing"
      (is ((broken [rapide {:type :deduction
                            :minor 0
                            :content {:inputs []}}])
           :citation/inputs)))
    (testing "a belief may cite nothing"
      (is (empty? (broken [rapide {:type :belief
                                   :minor 0
                                   :content {:inputs []}}]))))))
