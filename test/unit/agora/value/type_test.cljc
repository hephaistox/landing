(ns agora.value.type-test
  (:require
   [agora.value.lang :as lang]
   [agora.value.type :as sut]
   [clojure.test     :refer [deftest is testing]]))

(defn- broken [documents] (set (map :rule (mapcat #((:check %) documents) sut/rules))))

(def descriptions
  "One `type-<type>` definition per type and language."
  (for [t sut/types
        l lang/langs]
    {:type :definition
     :name (sut/description-name t)
     :lang l
     :content {:text "un type."
               :title (name t)}}))

(deftest description-test
  (is (= "type-measurable-fact" (sut/description-name :measurable-fact)))
  (is (sut/description? {:name "type-definition"}))
  (is (not (sut/description? {:name "rapide"}))))

(deftest rules-test
  (is (empty? (broken descriptions)))
  (testing "an unknown type"
    (is ((broken (conj descriptions
                       {:type :oeuvre
                        :content {:text "x"}}))
         :type/known)))
  (testing "the old :kind"
    (is ((broken (conj descriptions
                       {:type :definition
                        :kind :definition
                        :content {:text "x"}}))
         :type/known)))
  (testing "a type used in a language where it has no description"
    (is ((broken (conj (remove #(and (= "type-work" (:name %)) (= :en (:lang %))) descriptions)
                       {:type :work
                        :lang :en
                        :author {:id #uuid "00000000-0000-4000-8000-000000000001"}
                        :content {:text nil}}))
         :type/described)))
  (testing "nothing to describe when nothing is written" (is (empty? (broken []))))
  (testing "a description of an unknown type"
    (is ((broken (conj descriptions
                       {:type :definition
                        :name "type-oeuvre"
                        :lang :fr
                        :content {:text "x"}}))
         :type/described)))
  (testing "a work without a credited author"
    (is ((broken (conj descriptions
                       {:type :work
                        :content {:text nil}}))
         :type/text)))
  (testing "a work may have a text"
    (is (empty? (broken (conj descriptions
                              {:type :work
                               :author {:id #uuid "00000000-0000-4000-8000-000000000001"}
                               :content {:text "Roman de 1862."}})))))
  (testing "any other document has a text"
    (is ((broken (conj descriptions
                       {:type :definition
                        :name "rapide"
                        :content {:text nil}}))
         :type/text))))

(deftest definitions-test
  (testing "every type is defined once" (is (= (count sut/definitions) (count sut/types))))
  (doseq [{:keys [type inputs opening objections]} sut/definitions]
    (testing (str type)
      (is (#{:none :optional :required :one-work} inputs))
      (when opening
        (is (#{:author :term :target} (:subject opening)))
        (is (= #{:fr :en} (set (keys (:phrase opening))))))
      (doseq [{:keys [id label backed-by]} objections]
        (is (keyword? id))
        (is (= #{:fr :en} (set (keys label))) (str id))
        (is (every? sut/types backed-by) (str id)))))
  (testing "a publication and an objection are not contested"
    (is (empty? (:objections (sut/definition-of :publication))))
    (is (empty? (:objections (sut/definition-of :objection)))))
  (testing "a definition is completed, never contradicted"
    (is (sut/objection-of :definition :incomplete))
    (is (nil? (sut/objection-of :definition :contradicted))))
  (testing "a general claim is objected by a counter-example"
    (is (= #{:counter-example} (:backed-by (sut/objection-of :induction :counter-example))))))

(deftest consequence-of-test
  (is (= :extract (sut/consequence-of :work)))
  (is (= :definition (sut/consequence-of :extract)))
  (is (= :deduction (sut/consequence-of :measurable-fact))))
