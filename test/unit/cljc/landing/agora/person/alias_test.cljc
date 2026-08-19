(ns landing.agora.person.alias-test
  (:require
   [clojure.string                   :as str]
   [clojure.test                     :refer [deftest is testing]]
   [landing.agora.person.alias       :as sut]
   [landing.agora.person.alias-words :as w]))

(deftest alias-of-is-pure-and-total
  (testing "the same number always names the same alias"
    (is (= (sut/alias-of 4242 :fr) (sut/alias-of 4242 :fr))))
  (testing "any number names one — out of range and negative wrap"
    (is (= (sut/alias-of 0 :fr) (sut/alias-of (sut/alias-count :fr) :fr)))
    (is (not (str/blank? (sut/alias-of -1 :fr)))))
  (testing "an unknown language falls back rather than yielding nothing"
    (is (= (sut/alias-of 7 :fr) (sut/alias-of 7 :xx))))
  (testing "a language is named by keyword or string alike"
    (is (= (sut/alias-of 7 :en) (sut/alias-of 7 "en")))))

(deftest alias-reads-as-its-language
  (testing "French puts the noun first, English the adjective"
    (is (= "Prémisse Cuivrée" (sut/alias-of 0 :fr)))
    (is (= "Coppered Premise" (sut/alias-of 0 :en))))
  (testing "both words are capitalized"
    (let [capitalized? (fn [w] (= (subs w 0 1) (str/upper-case (subs w 0 1))))]
      (is (every? #(every? capitalized? (str/split (sut/alias-of % :fr) #" "))
                  (range 0 2000 97))))))

(deftest the-adjective-agrees-with-the-noun
  (testing "a feminine noun takes the feminine form, a masculine one the masculine"
    (is (= "Prémisse Vermeille" (sut/alias-of 4 :fr)) "prémisse is feminine")
    (is (str/ends-with? (sut/alias-of 1 :fr) "Dorée") "prémisse is feminine")
    (is (str/ends-with? (sut/alias-of (inc (count (get-in w/words [:fr :adjectives]))) :fr) "Doré")
        "argument is masculine"))
  (testing "every French adjective declares both forms, every French noun its gender"
    (is (every? #(= 2 (count %)) (get-in w/words [:fr :adjectives])))
    (is (every? #(contains? #{:m :f} (second %)) (get-in w/words [:fr :nouns])))))

(deftest the-vocabulary-holds-no-duplicate
  (testing "a repeated word would silently shrink the alias space"
    (doseq [lang [:fr :en]]
      (let [{:keys [adjectives nouns]} (get w/words lang)]
        (is (= (count adjectives) (count (distinct (map first adjectives))))
            (str lang " adjectives"))
        (is (= (count nouns) (count (distinct (map first nouns)))) (str lang " nouns")))))
  (testing "the space is wide enough that a collision is a rarity, not a rule"
    (is (< 50000 (sut/alias-count :fr)))
    (is (< 50000 (sut/alias-count :en)))))

(deftest alias-key-normalizes-what-uniqueness-is-held-on
  (is (= "premisse cuivree" (sut/alias-key "  Prémisse   Cuivrée ")))
  (testing "two spellings of one name make one claim"
    (is (= (sut/alias-key "Prémisse Cuivrée") (sut/alias-key "PREMISSE cuivree"))))
  (testing "distinct aliases keep distinct keys"
    (is (= (count (distinct (map #(sut/alias-key (sut/alias-of % :fr)) (range 3000))))
           (count (distinct (map #(sut/alias-of % :fr) (range 3000))))))))
