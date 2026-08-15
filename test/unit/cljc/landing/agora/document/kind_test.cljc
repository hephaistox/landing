(ns landing.agora.document.kind-test
  "Pure register rules — kinds and their capabilities, the kind-guided statement scaffold, and
  prose block structure. No database, no adapter. Runs in clj (and would in cljs). Identity and
  edges are tested in `landing.agora.document.identity-test`."
  (:require
   [clojure.test                :refer [deftest is testing]]
   [landing.agora.document.kind :as sut]))

(deftest kind-data
  (is (= (count sut/kinds) (count (distinct sut/kind-ids))) "kind ids are unique")
  (is (every? sut/kind-color sut/kind-ids) "every kind has an accent colour")
  (is (seq (sut/kind-ids-of "ki")) "KI kinds exist")
  (is (seq (sut/kind-ids-of "article")) "article kinds exist")
  (is (not-any? (set (sut/kind-ids-of "ki")) (sut/kind-ids-of "article"))
      "KI and article kind sets are disjoint"))

(deftest kind-capabilities
  (testing "kinds partition by object type; the first id is that type's default"
    (is (= :inference (first (sut/kind-ids-of "ki"))) "inference is the KI default")
    (is (= :explainer (first (sut/kind-ids-of "article"))) "explainer is the article default")
    (is (every? #(= :ki (:object-type %)) (sut/kinds-of "ki")) "object-type is the :ki keyword")
    (is (= (sut/kinds-of :ki) (sut/kinds-of "ki")) "kinds-of coerces string ↔ keyword type"))
  (testing "kind-allows-inputs?: only a work is inputless; an absent kind (article) defaults to yes"
    (is (sut/kind-allows-inputs? :inference))
    (is (sut/kind-allows-inputs? :definition))
    (is (sut/kind-allows-inputs? :extract) "an extract cites the work it draws from")
    (is (not (sut/kind-allows-inputs? :work)) "a work is a leaf")
    (is (sut/kind-allows-inputs? nil) "an article has no kind → may take inputs"))
  (testing "kind-citations-in-text?: every kind is cited in-text"
    (is (sut/kind-citations-in-text? :definition))
    (is (sut/kind-citations-in-text? :extract)))
  (testing "kind-def points each kind at its self-hosting definition KI"
    (is (= {:type :ki
            :name "type-inference"
            :major 1}
           (get sut/kind-def :inference)))
    (is (= {:type :ki
            :name "type-extract"
            :major 1}
           (get sut/kind-def :extract))))
  (testing "every kind carries an accent colour" (is (every? sut/kind-color sut/kind-ids))))

(deftest statement-scaffold
  (testing "author-kinds open with '<Author> <verb> that '"
    (is (= "Sun Tzŭ believes that " (sut/statement-prefix :belief :en "Sun Tzŭ")))
    (is (= "Sun Tzŭ suppose que " (sut/statement-prefix :assumption :fr "Sun Tzŭ"))))
  (testing "a definition opens with '<Term> designates '"
    (is (= "Quick designates " (sut/statement-prefix :definition :en "quick"))))
  (testing "a free-form inference has no prefix and no subject"
    (is (nil? (sut/statement-prefix :inference :en "x")))
    (is (nil? (sut/statement-subject-kind :inference))))
  (testing "an unknown language falls back to English"
    (is (= "X believes that " (sut/statement-prefix :belief :de "x"))))
  (testing "attributed-author is the document's own author — a work's is its cited author"
    (is (= "Sun Tzŭ" (sut/attributed-author {:author "Sun Tzŭ"}))
        "a work is attributed to its cited author (stored as its own :author)")
    (is (= "Poster" (sut/attributed-author {:author "Poster"}))
        "an extract belongs to whoever wrote it, like any document"))
  (testing "attributed-author-id: a work links via its cited-author :author-id, else via :owner-id"
    (is (= "suntzu-id"
           (sut/attributed-author-id {:author-id "suntzu-id"
                                      :owner-id "creator-id"}))
        "a work links to its cited author, not the contributor who added the record")
    (is (= "owner-id" (sut/attributed-author-id {:owner-id "owner-id"}))
        "a document with no :author-id links to its owner"))
  (testing "compose-statement assembles prefix + body for a document"
    (is (= "Anthony believes that reasoning is fuzzy."
           (sut/compose-statement {:kind :belief
                                   :author "Anthony"}
                                  :en
                                  "reasoning is fuzzy.")))
    (is (= "just the body" (sut/compose-statement {:kind :inference} :en "just the body")))))

(deftest prose-structure
  (testing "parse-blocks groups bullet runs and paragraph runs, dropping blank separators"
    (is (= [{:type :p
             :lines ["Intro line" "second line"]}
            {:type :ul
             :items ["one" "two"]}
            {:type :p
             :lines ["Closing."]}]
           (sut/parse-blocks "Intro line\nsecond line\n\n- one\n- two\n\nClosing.")))))
