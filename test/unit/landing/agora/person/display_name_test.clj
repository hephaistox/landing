(ns landing.agora.person.display-name-test
  (:require
   [clojure.string                          :as str]
   [clojure.test                            :refer [deftest is testing]]
   [landing.agora.person.display-name       :as sut]
   [landing.agora.person.display-name-words :as w]))

(deftest display-name-of-is-pure-and-total
  (testing "the same number always names the same display name"
    (is (= (sut/display-name-of 4242 :fr) (sut/display-name-of 4242 :fr))))
  (testing "any number names one — out of range and negative wrap"
    (is (= (sut/display-name-of 0 :fr) (sut/display-name-of (sut/display-name-count :fr) :fr)))
    (is (not (str/blank? (sut/display-name-of -1 :fr)))))
  (testing "an unknown language falls back rather than yielding nothing"
    (is (= (sut/display-name-of 7 :fr) (sut/display-name-of 7 :xx))))
  (testing "a language is named by keyword or string alike"
    (is (= (sut/display-name-of 7 :en) (sut/display-name-of 7 "en")))))

(deftest display-name-reads-as-its-language
  (testing "French puts the noun first, English the adjective"
    (is (= "Prémisse Cuivrée" (sut/display-name-of 0 :fr)))
    (is (= "Coppered Premise" (sut/display-name-of 0 :en))))
  (testing "both words are capitalized"
    (let [capitalized? (fn [w] (= (subs w 0 1) (str/upper-case (subs w 0 1))))]
      (is (every? #(every? capitalized? (str/split (sut/display-name-of % :fr) #" "))
                  (range 0 2000 97))))))

(deftest the-adjective-agrees-with-the-noun
  (testing "a feminine noun takes the feminine form, a masculine one the masculine"
    (is (= "Prémisse Vermeille" (sut/display-name-of 4 :fr)) "prémisse is feminine")
    (is (str/ends-with? (sut/display-name-of 1 :fr) "Dorée") "prémisse is feminine")
    (is (str/ends-with? (sut/display-name-of (inc (count (get-in w/words [:fr :adjectives]))) :fr)
                        "Doré")
        "argument is masculine"))
  (testing "every French adjective declares both forms, every French noun its gender"
    (is (every? #(= 2 (count %)) (get-in w/words [:fr :adjectives])))
    (is (every? #(contains? #{:m :f} (second %)) (get-in w/words [:fr :nouns])))))

(deftest the-vocabulary-holds-no-duplicate
  (testing "a repeated word would silently shrink the display name space"
    (doseq [lang [:fr :en]]
      (let [{:keys [adjectives nouns]} (get w/words lang)]
        (is (= (count adjectives) (count (distinct (map first adjectives))))
            (str lang " adjectives"))
        (is (= (count nouns) (count (distinct (map first nouns)))) (str lang " nouns")))))
  (testing "the space is wide enough that a collision is a rarity, not a rule"
    (is (< 50000 (sut/display-name-count :fr)))
    (is (< 50000 (sut/display-name-count :en)))))

(deftest display-name-key-normalizes-what-uniqueness-is-held-on
  (is (= "premisse cuivree" (sut/display-name-key "  Prémisse   Cuivrée ")))
  (testing "two spellings of one name make one claim"
    (is (= (sut/display-name-key "Prémisse Cuivrée") (sut/display-name-key "PREMISSE cuivree"))))
  (testing "distinct display names keep distinct keys"
    (is (= (count (distinct (map #(sut/display-name-key (sut/display-name-of % :fr)) (range 3000))))
           (count (distinct (map #(sut/display-name-of % :fr) (range 3000))))))))
