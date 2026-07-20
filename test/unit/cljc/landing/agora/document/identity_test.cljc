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
  (is (= [] (sut/cite-refs "See [[non-existing-type:confidence-is-partial@1|partial]] here." "fr"))
      "Non existing type is skipped")
  (is
   (=
    [{:type :ki
      :name "confidence-is-partial"
      :lang "fr"
      :major 1}
     {:type :ki
      :name "confidence-over-binary"
      :lang "fr"
      :major 2}]
    (sut/cite-refs
     "See [[ki:confidence-is-partial@1|partial]] then [[ki:confidence-over-binary@2]] here. [[fake:@1]]"
     "fr"))
   "extracts cited KIs from body tokens as ki-input TNLRs in the given lang")
  (testing "dedupes repeated citations and tolerates no tokens"
    (is (= [{:type :ki
             :name "a"
             :lang "en"
             :major 1}]
           (sut/cite-refs "[[ki:a@1]] and again [[ki:a@1]]" "en")))
    (is (= [] (sut/cite-refs "no citations here" "fr")))
    (is (= [] (sut/cite-refs nil "fr"))))
  (testing "a token's own language overrides the fallback (cross-language citation)"
    (is (= [{:type :ki
             :name "a"
             :lang :en
             :major 1}
            {:type :ki
             :name "b"
             :lang :fr
             :major 2}]
           (sut/cite-refs "[[ki:a:en@1]] and [[ki:b:fr@2|label]]" "fr")))))

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

(deftest pinned
  (is (sut/pinned? (assoc t-a :id "x")) "an inline :id marks a pinned (frozen) input")
  (is (not (sut/pinned? t-a)) "a bare TNLR is floating"))

(deftest declarations
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
  (is (= {(sut/tnlr-key t-a) "a-latest"
          (sut/tnlr-key t-b) "b-latest"}
         (sut/pin-all [t-a t-b] #(str (:name %) "-latest")))
      "pin-all resolves every floating declaration to its latest id, keyed by tnlr-key")
  (is (= {(sut/tnlr-key t-a) "frozen"
          (sut/tnlr-key t-b) "b-latest"}
         (sut/pin-all [(assoc t-a :id "frozen") t-b] #(str (:name %) "-latest")))
      "a pinned input keeps its frozen id — never resolved")
  (is (= {(sut/tnlr-key t-a) "a2"
          (sut/tnlr-key t-b) "b1"}
         (sut/repin {(sut/tnlr-key t-a) "a1"
                     (sut/tnlr-key t-b) "b1"}
                    t-a
                    "a2"))
      "repin updates only the matching TNLR's pin"))

(deftest refs-and-successors
  (testing "input-refs zip declarations with their pins (nil id when unpinned)"
    (is (= [(assoc t-a :id "a1") (assoc t-b :id nil)]
           (sut/input-refs [t-a t-b] {(sut/tnlr-key t-a) "a1"}))))
  (testing "input-refs prefers a pinned input's own :id over the (stale) pin cache"
    (is (= [(assoc t-a :id "frozen")]
           (sut/input-refs [(assoc t-a :id "frozen")] {(sut/tnlr-key t-a) "stale"})))))

(deftest successor-tuples-tes
  (is (= [{:tnlr t-a
           :successor-id "s"}
          {:tnlr t-b
           :successor-id "s"}]
         (sut/successor-tuples "s" [t-a t-b]))
      "successor-tuples: one row per declaration"))
