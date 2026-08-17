(ns landing.agora.document.identity-test
  "Pure identity & edge rules — TNLR, cid/slug, the citation grammar, inputs, pins and
  successors. No database, no adapter. Runs in clj (and would in cljs)."
  (:require
   [clojure.test                    :refer [deftest is testing]]
   [landing.agora.document.identity :as sut]))

;; --- Stubs --------------------------------------------------------------

(def t-a
  {:type :ki
   :name "a"
   :lang "fr"
   :major 1})

(def t-b
  {:type :ki
   :name "b"
   :lang "fr"
   :major 1})

(deftest identity-constants (is (pos-int? sut/max-inputs) "inputs are capped"))

;; --- Identity slug & cid --------------------------------------------------------------

(deftest slugify-test
  (is (= "le-chien" (sut/slugify "Le chien")))
  (is (= "l-avocat-est-il-mangeable" (sut/slugify "L'avocat est-il mangeable?"))))

(deftest permalink-and-cid
  (testing "permalink-slug decorates the cid with a title slug; cid-of recovers just the cid"
    (let [k (sut/permalink-slug "aB3xz" "L'Être et le Néant")]
      (is (= "aB3xz~l-etre-et-le-neant" k))
      (is (= "aB3xz" (sut/cid-of k)))))
  (testing "a title yielding no slug leaves a bare cid, and cid-of is stable across any slug"
    (is (= "aB3xz" (sut/permalink-slug "aB3xz" "?!")))
    (is (= "aB3xz" (sut/cid-of "aB3xz")))
    (is (= "aB3xz" (sut/cid-of "aB3xz~any-old-title")))))

;; --- TNLR -----------------------------------------------------------------------------

(deftest tnlr
  (is (= [:ki "a" "fr" 1] (sut/tnlr-key t-a)))
  (is (= t-a (sut/tnlr (assoc t-a :minor 3 :id "x"))) "strips non-identity fields")
  (is (sut/same-tnlr? t-a (assoc t-a :minor 9 :id "z")) "identity ignores minor/id")
  (is (not (sut/same-tnlr? t-a t-b))))

;; --- citation grammar (the input link, expressed in prose) ----------------------------

(deftest cite-refs
  (is (= [] (sut/cite-refs "See [[non-existing-type:confidence-is-partial@1|partial]] here."))
      "Non existing type is skipped")
  (is
   (=
    [{:type :ki
      :name "confidence-is-partial"
      :lang nil
      :major 1}
     {:type :ki
      :name "confidence-over-binary"
      :lang nil
      :major 2}]
    (sut/cite-refs
     "See [[ki:confidence-is-partial@1|partial]] then [[ki:confidence-over-binary@2]] here. [[fake:@1]]"))
   "a bare token has nil lang — the consumer fills the context language")
  (testing "dedupes repeated citations and tolerates no tokens"
    (is (= [{:type :ki
             :name "a"
             :lang nil
             :major 1}]
           (sut/cite-refs "[[ki:a@1]] and again [[ki:a@1]]")))
    (is (= [] (sut/cite-refs "no citations here")))
    (is (= [] (sut/cite-refs nil))))
  (testing "a token's own language is kept (cross-language citation)"
    (is (= [{:type :ki
             :name "a"
             :lang :en
             :major 1}
            {:type :ki
             :name "b"
             :lang :fr
             :major 2}]
           (sut/cite-refs "[[ki:a:en@1]] and [[ki:b:fr@2|label]]")))))

(deftest strip-cite
  (testing "strip-cite removes a dropped input's citation, keeping its display text"
    (is (= "See partial then [[ki:other@2]] here."
           (sut/strip-cite "See [[ki:confidence@1|partial]] then [[ki:other@2]] here."
                           {:type :ki
                            :name "confidence"
                            :lang nil
                            :major 1})))
    (is (= "See partial then other here."
           (-> "See [[ki:confidence@1|partial]] then [[ki:other@2]] here."
               (sut/strip-cite {:type :ki
                                :name "confidence"
                                :lang nil
                                :major 1})
               (sut/strip-cite {:type :ki
                                :name "other"
                                :lang nil
                                :major 2}))))
    (is (= "keep partial."
           (sut/strip-cite "keep [[ki:x:en@1|partial]]."
                           {:type :ki
                            :name "x"
                            :lang :en
                            :major 1}))
        "a lang-carrying token strips by name+major too"))
  (testing "type is part of the citation identity: dropping a :ki leaves an [[article:…]] alone"
    (is (= "keep [[article:x@1|partial]]."
           (sut/strip-cite "keep [[article:x@1|partial]]."
                           {:type :ki
                            :name "x"
                            :lang :en
                            :major 1})))))

;; --- inputs -------------------------------------------------------------------

(deftest input-test
  (is (= [t-b (assoc t-a :id "x")]
         (-> []
             (sut/add-input t-a)
             (sut/add-input t-b)
             (sut/add-input (assoc t-a :id "x"))))
      "add-declared dedups by lineage and keeps a pin (:id) when the input carries one")
  (is (= [t-b]
         (-> []
             (sut/add-input (assoc t-a :id "x"))
             (sut/add-input t-b)
             (sut/drop-input t-a)))
      "drop-input removes by lineage, pinned or floating"))

(deftest pins
  (testing "pin-all sets each input's :id — its lineage's latest for a floating input"
    (is (= [(assoc t-a :id "a-latest") (assoc t-b :id "b-latest")]
           (sut/pin-all [t-a t-b] #(str (:name %) "-latest")))))
  (testing "a pinned input keeps its own frozen :id — never re-resolved"
    (is (= [(assoc t-a :id "frozen") (assoc t-b :id "b-latest")]
           (sut/pin-all [(assoc t-a :id "frozen") t-b] #(str (:name %) "-latest")))))
  (testing "repin sets only the matching input's :id, leaving the others"
    (is (= [(assoc t-a :id "a2") (assoc t-b :id "b1")]
           (sut/repin [(assoc t-a :id "a1") (assoc t-b :id "b1")] t-a "a2")))))

(deftest successor-tuples-tes
  (is (= [{:tnlr t-a
           :successor-id "s"}
          {:tnlr t-b
           :successor-id "s"}]
         (sut/successor-tuples "s" [t-a t-b]))
      "successor-tuples: one row per declaration"))
