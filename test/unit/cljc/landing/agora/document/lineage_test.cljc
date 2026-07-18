(ns landing.agora.document.lineage-test
  "Pure lineage rules — resolution over a set of minors, the create/edit/publish constructors, and
  the 'text → inputs' derivation. No database, no adapter; a lineage is just a seq of version
  maps in, a value out."
  (:require
   [clojure.test                   :refer [deftest is testing]]
   [landing.agora.document.lineage :as sut]))

(def minors
  "One lineage's versions: v0/v1 published, v2 a draft (an in-progress edit)."
  [{:type :ki
    :name "a"
    :lang :en
    :major 1
    :minor 0
    :draft false
    :kind "inference"
    :title "A v0"}
   {:type :ki
    :name "a"
    :lang :en
    :major 1
    :minor 1
    :draft false
    :kind "inference"
    :title "A v1"}
   {:type :ki
    :name "a"
    :lang :en
    :major 1
    :minor 2
    :draft true
    :kind "inference"
    :title "A v2"}])

;; --- content & the input-derivation rule -------------------------------------

(deftest input-derivation
  (testing "inputs are the TNLRs of the [[ki:…]] citations in the text, in the given lang"
    (is (= [{:type :ki
             :name "fuzzy"
             :lang :en
             :major 1}]
           (sut/inputs-of {:kind "inference"
                           :text "builds on [[ki:fuzzy@1]]."}
                          :en))))
  (testing "a citation's own language overrides the fallback (cross-language input)"
    (is (= [{:type :ki
             :name "a"
             :lang :fr
             :major 1}]
           (sut/inputs-of {:kind "inference"
                           :text "cites [[ki:a:fr@1]]"}
                          :en))))
  (testing "no citations → no inputs"
    (is (= []
           (sut/inputs-of {:kind "inference"
                           :text "a standalone claim."}
                          :en))))
  (testing "a source kind takes no inputs even if its text has tokens"
    (is (= []
           (sut/inputs-of {:kind "source"
                           :text "[[ki:x@1]]"}
                          :en))))
  (testing "explicit source :quotes are folded in as ki inputs (edge-only, not in prose)"
    (is (= [{:type :ki
             :name "q"
             :lang :en
             :major 3}]
           (sut/inputs-of {:kind "definition"
                           :text ""
                           :quotes [{:name "q"
                                     :major 3}]}
                          :en)))))

;; --- resolution over a lineage's minors --------------------------------------

(deftest resolution
  (testing "latest is the highest minor, drafts included"
    (is (= 2 (:minor (sut/latest-with-drafts minors))))
    (is (nil? (sut/latest-with-drafts []))))
  (testing "latest-published is the highest non-draft minor, or nil when draft-only"
    (is (= 1 (:minor (sut/latest-published minors))))
    (is (nil? (sut/latest-published [{:minor 0
                                      :draft true}]))))
  (testing "next-minor is one past the highest, or 0 for an empty lineage"
    (is (= 3 (sut/next-minor minors)))
    (is (= 0 (sut/next-minor [])))))

;; --- lifecycle --------------------------------------

(deftest lifecycle
  (testing "create → a draft major-1/minor-0 version with inputs derived from its text"
    (let [v (sut/create {:type :ki
                         :name "x"
                         :lang :en
                         :kind "inference"
                         :title "X"
                         :text "see [[ki:a@1]]"})]
      (is (= {:major 1
              :minor 0
              :draft true}
             (select-keys v [:major :minor :draft])))
      (is (= [{:type :ki
               :name "a"
               :lang :en
               :major 1}]
             (:inputs v)))))
  (testing "edit → a new minor, carrying the current forward, re-deriving inputs from the new text"
    (let [v (sut/edit minors {:text "now cites [[ki:b@2]]"})]
      (is (= 3 (:minor v)) "one past the highest")
      (is (true? (:draft v)) "an edit lands as a draft")
      (is (= "A v2" (:title v)) "carried from the current version")
      (is (= [{:type :ki
               :name "b"
               :lang :en
               :major 2}]
             (:inputs v)))))
  (testing "edit drops per-version fields so the caller reassigns them"
    (let [v (sut/edit [{:type :ki
                        :name "a"
                        :lang :en
                        :major 1
                        :minor 0
                        :id "old"
                        :pins {:x 1}
                        :kind "inference"
                        :text ""}]
                      {})]
      (is (nil? (:id v)))
      (is (nil? (:pins v)))))
  (testing "edit of an empty lineage is nil" (is (nil? (sut/edit [] {:title "x"})))))

(deftest close-publish
  (testing "publish closes the publication and publishes every draft it gathers, at once"
    (let [publication {:type :publication
                       :name "p"
                       :lang :en
                       :major 1
                       :minor 0
                       :status "open"
                       :draft true
                       :title "P"}
          members [{:type :ki
                    :name "a"
                    :lang :en
                    :major 1
                    :minor 3
                    :draft true}
                   {:type :ki
                    :name "b"
                    :lang :en
                    :major 1
                    :minor 0
                    :draft true}]
          [pub' & members'] (sut/publish publication members)]
      (is (= {:status "closed"
              :draft false}
             (select-keys pub' [:status :draft]))
          "the publication becomes closed + published")
      (is (= 2 (count members')))
      (is (every? (comp false? :draft) members') "every member draft becomes published")))
  (testing "a publication with no members still closes"
    (is (= {:status "closed"
            :draft false}
           (select-keys (first (sut/publish {:status "open"
                                             :draft true}
                                            []))
                        [:status :draft])))))

;; --- a version's declared inputs ---------------------------------------------

(deftest declared-inputs
  (testing "declared-inputs reads a version's stored :inputs (empty for a leaf)"
    (is (= [{:type :ki
             :name "def"
             :lang :en
             :major 1}]
           (sut/declared-inputs {:type :ki
                                 :name "claim"
                                 :inputs [{:type :ki
                                           :name "def"
                                           :lang :en
                                           :major 1}]})))
    (is (= []
           (sut/declared-inputs {:type :ki
                                 :name "leaf"})))))

;; --- referential-integrity rules ---------------------------------------------

(deftest pending?
  (testing "the inputs with no published version, per the injected predicate"
    (let [published? #{{:type :ki
                        :name "ok"
                        :lang :en
                        :major 1}}
          inputs [{:type :ki
                   :name "ok"
                   :lang :en
                   :major 1}
                  {:type :ki
                   :name "draft-only"
                   :lang :en
                   :major 1}]]
      (is (= [{:type :ki
               :name "draft-only"
               :lang :en
               :major 1}]
             (sut/pending? inputs published?)))
      (is (= [] (sut/pending? [] published?))))))

(deftest ref-issues
  (testing "flags dangling references (a ref whose lineage is not in `existing`)"
    (let [doc {:type :ki
               :name "claim"
               :lang :en
               :major 1
               :inputs [{:type :ki
                         :name "def"
                         :lang :en
                         :major 1}
                        {:type :ki
                         :name "ghost"
                         :lang :en
                         :major 1}]}
          {:keys [broken self]} (sut/ref-issues doc #{[:ki "def" 1]})]
      (is (= [{:name "ghost"
               :major 1
               :lang :en}]
             broken))
      (is (= [] self))))
  (testing "flags a self-reference (a doc citing its own lineage)"
    (is (= [{:name "claim"
             :major 1
             :lang :en}]
           (:self (sut/ref-issues {:type :ki
                                   :name "claim"
                                   :lang :en
                                   :major 1
                                   :inputs [{:type :ki
                                             :name "claim"
                                             :lang :en
                                             :major 1}]}
                                  #{[:ki "claim" 1]})))))
  (testing "re-derives text citations too, so it catches drift between :inputs and the text"
    (is (= [{:name "cited"
             :major 2
             :lang :fr}]
           (:broken (sut/ref-issues {:type :ki
                                     :name "x"
                                     :lang :fr
                                     :major 1
                                     :inputs []
                                     :text "see [[ki:cited@2]]"}
                                    #{}))))))

(deftest consistency-issues
  (let [existing #{[:ki "def" 1] [:ki "claim" 1]}
        clean {:id "d1"
               :type :ki
               :name "claim"
               :lang :en
               :major 1
               :minor 0
               :title "Claim"
               :inputs [{:type :ki
                         :name "def"
                         :lang :en
                         :major 1}]}
        broken {:id "d2"
                :type :ki
                :name "claim"
                :lang :en
                :major 1
                :minor 1
                :title "Claim v1"
                :inputs [{:type :ki
                          :name "ghost"
                          :lang :en
                          :major 1}]}
        self {:id "d3"
              :type :ki
              :name "def"
              :lang :en
              :major 1
              :minor 0
              :title "Def"
              :inputs [{:type :ki
                        :name "def"
                        :lang :en
                        :major 1}]}]
    (testing "one shaped entry per version with a broken or self reference; clean ones omitted"
      (is
       (= [{:id "d2"
            :type :ki
            :name "claim"
            :lang :en
            :major 1
            :minor 1
            :title "Claim v1"
            :broken [{:name "ghost"
                      :major 1
                      :lang :en}]
            :self []}
           {:id "d3"
            :type :ki
            :name "def"
            :lang :en
            :major 1
            :minor 0
            :title "Def"
            :broken []
            :self [{:name "def"
                    :major 1
                    :lang :en}]}]
          (sut/consistency-issues [clean broken self] existing))))
    (testing "a clean set yields no issues" (is (= [] (sut/consistency-issues [clean] existing))))))
