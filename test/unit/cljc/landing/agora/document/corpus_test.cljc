(ns landing.agora.document.corpus-test
  "The `landing.agora.document.corpus` substrate (env/cli) — a corpus is a flat vector of versions. This
  tests the substrate-specific behaviour: the `create`/`edit` appends (deriving inputs from the
  text through `document.lineage`) and the whole-corpus **graph queries** (`resolve-latest`,
  `resolved-inputs`, `publication-of`, `members`, `latest-lineages`) — each the corpus scan
  composed with a pure `document.lineage` rule. The pure rules themselves live in
  `document.lineage-test`; the compact prints are `corpus-print`'s. All asserted over the worked
  example (`env/cli/agora/corpus-example.edn`)."
  (:require
   [clojure.test                        :refer [deftest is testing]]
   [landing.agora.document.corpus       :as sut]
   [landing.agora.document.corpus-print :as cprint]
   [landing.agora.document.lineage      :as lineage]))

(def corpus
  "A worked corpus fixture, inlined as cljc **data** so the test runs on clj and cljs alike — the
  file-loading (`slurp`) is the CLI's clj adapter, not the test's. Mirrors the CLI's loadable
  sample `env/cli/agora/corpus-example.edn`: a definition edited once (two minors) + its fr
  sibling, an inference and an article citing it (drafts in the meta-graph publication), two
  coexisting beliefs in the theology publication, and the two publication documents."
  [{:author "Anthony"
    :draft false
    :kind "definition"
    :lang "en"
    :major 1
    :minor 0
    :name "fuzzy-confidence"
    :text "collective confidence in a claim that is partial and evolving, never binary."
    :title "Fuzzy confidence"
    :type :ki}
   {:author "Anthony"
    :draft false
    :kind "definition"
    :lang "en"
    :major 1
    :minor 1
    :name "fuzzy-confidence"
    :text
    "the collective, partial, evolving confidence a community holds in a claim — never a binary true/false."
    :title "Fuzzy confidence"
    :type :ki}
   {:author "Anthony"
    :draft false
    :kind "definition"
    :lang "fr"
    :major 1
    :minor 0
    :name "fuzzy-confidence"
    :text
    "la confiance collective, partielle et évolutive d'une communauté dans une affirmation — jamais un vrai/faux binaire."
    :title "Confiance floue"
    :type :ki}
   {:author "Anthony"
    :draft true
    :inputs [{:lang "en"
              :major 1
              :name "fuzzy-confidence"
              :type :ki}]
    :kind "inference"
    :lang "en"
    :major 1
    :minor 0
    :name "reason-is-fuzzy"
    :publication {:lang "en"
                  :major 1
                  :name "meta-graph"
                  :type :publication}
    :text
    "Because knowledge carries only [[ki:fuzzy-confidence:en@1]], reasoning about it inherits that partiality."
    :title "Human reasoning is fuzzy"
    :type :ki}
   {:author "Anthony"
    :draft true
    :inputs [{:lang "en"
              :major 1
              :name "fuzzy-confidence"
              :type :ki}
             {:lang "en"
              :major 1
              :name "reason-is-fuzzy"
              :type :ki}]
    :kind "explainer"
    :lang "en"
    :major 1
    :minor 0
    :name "on-partial-knowledge"
    :publication {:lang "en"
                  :major 1
                  :name "meta-graph"
                  :type :publication}
    :text
    "Agora stores reasoning, not conclusions: every step carries [[ki:fuzzy-confidence:en@1|fuzzy confidence]], and so [[ki:reason-is-fuzzy:en@1]] follows."
    :title "On partial knowledge"
    :type :article}
   {:author "A. Believer"
    :draft true
    :kind "belief"
    :lang "en"
    :major 1
    :minor 0
    :name "god-exists"
    :publication {:lang "en"
                  :major 1
                  :name "theology"
                  :type :publication}
    :text "a personal, uncaused ground of being underlies reality."
    :title "God exists"
    :type :ki}
   {:author "A. Skeptic"
    :draft true
    :kind "belief"
    :lang "en"
    :major 1
    :minor 0
    :name "god-absent"
    :publication {:lang "en"
                  :major 1
                  :name "theology"
                  :type :publication}
    :text "reality is sufficient unto itself; no ground of being is needed."
    :title "God does not exist"
    :type :ki}
   {:author "Anthony"
    :draft true
    :lang "en"
    :major 1
    :minor 0
    :name "meta-graph"
    :status "open"
    :title "The meta-graph of Agora"
    :type :publication}
   {:author "Anthony"
    :draft true
    :lang "en"
    :major 1
    :minor 0
    :name "theology"
    :status "open"
    :title "Does God exist?"
    :type :publication}])

(defn- tnlr
  [type name lang]
  {:type type
   :name name
   :lang lang
   :major 1})

(defn- current
  "The current version of a lineage in the corpus — the peek + the domain resolver, composed."
  [corpus t]
  (lineage/latest-with-drafts (sut/versions corpus t)))

(deftest create-and-edit-append-to-the-corpus
  (testing "create appends a draft major-1/minor-0 document, deriving :inputs from its text"
    (let [[c' d] (sut/create corpus
                             {:type :ki
                              :name "new-claim"
                              :lang "en"
                              :kind "inference"
                              :title "A new claim"
                              :author "Tester"
                              :text "this builds on [[ki:fuzzy-confidence:en@1]]."})]
      (is (= [{:type :ki
               :name "fuzzy-confidence"
               :lang "en"
               :major 1}]
             (:inputs d))
          "inputs derived from the [[ki:…]] citation in the text")
      (is (= d (current c' (tnlr :ki "new-claim" "en"))) "and it is now the current version")))
  (testing "edit appends a new minor; the original corpus is untouched (immutable)"
    (let [[_ d]
          (sut/edit corpus (tnlr :ki "fuzzy-confidence" "en") {:title "Fuzzy confidence (v2)"})]
      (is (= 2 (:minor d)))
      (is (= "Fuzzy confidence (v2)" (:title d)))
      (is (= "Fuzzy confidence" (:title (current corpus (tnlr :ki "fuzzy-confidence" "en"))))
          "the original corpus is unchanged")))
  (testing "editing the text re-derives :inputs (removing a citation drops the input)"
    (let [[_ d] (sut/edit corpus
                          (tnlr :ki "reason-is-fuzzy" "en")
                          {:text "now standalone, with no citation."})]
      (is (= [] (:inputs d)))))
  (testing "editing an absent lineage is a no-op"
    (is (= [corpus nil] (sut/edit corpus (tnlr :ki "ghost" "en") {:title "x"})))))

(deftest compact-print
  (testing "a document renders as one dense, stable line"
    (is (= "ki fuzzy-confidence@1.1 en [definition] \"Fuzzy confidence\""
           (cprint/compact-doc (current corpus (tnlr :ki "fuzzy-confidence" "en")))))
    (is
     (=
      "ki reason-is-fuzzy@1.0 en [inference] \"Human reasoning is fuzzy\" ⇐ fuzzy-confidence@1 ∈ meta-graph (draft)"
      (cprint/compact-doc (current corpus (tnlr :ki "reason-is-fuzzy" "en")))))
    (is
     (=
      "article on-partial-knowledge@1.0 en [explainer] \"On partial knowledge\" ⇐ fuzzy-confidence@1, reason-is-fuzzy@1 ∈ meta-graph (draft)"
      (cprint/compact-doc (current corpus (tnlr :article "on-partial-knowledge" "en")))))
    (is (= "publication meta-graph@1.0 en <open> \"The meta-graph of Agora\" (draft)"
           (cprint/compact-doc (current corpus (tnlr :publication "meta-graph" "en"))))))
  (testing "a publication renders with its members indented beneath it"
    (is
     (=
      (str
       "publication meta-graph@1.0 en <open> \"The meta-graph of Agora\" (draft) — 2 members\n"
       "  • article on-partial-knowledge@1.0 en [explainer] \"On partial knowledge\" ⇐ fuzzy-confidence@1, reason-is-fuzzy@1 ∈ meta-graph (draft)\n"
       "  • ki reason-is-fuzzy@1.0 en [inference] \"Human reasoning is fuzzy\" ⇐ fuzzy-confidence@1 ∈ meta-graph (draft)")
      (cprint/compact-publication corpus (current corpus (tnlr :publication "meta-graph" "en"))))))
  (testing "the whole corpus lists every current lineage (documents, then publications expanded)"
    (let [dump (cprint/compact-corpus corpus)]
      (is (re-find #"ki fuzzy-confidence@1\.1 en" dump))
      (is (re-find #"ki fuzzy-confidence@1\.0 fr" dump))
      (is (re-find #"publication theology@1\.0 en <open>.*— 2 members" dump)))))

(deftest graph-queries
  (testing "resolve-latest narrows to a lineage and takes its current minor (drafts included)"
    (is (= 1 (:minor (sut/resolve-latest corpus (tnlr :ki "fuzzy-confidence" "en")))))
    (is (nil? (sut/resolve-latest corpus (tnlr :ki "ghost" "en")))))
  (testing "resolved-inputs resolves each declared input to its current version"
    (let [ins (sut/resolved-inputs corpus
                                   (sut/resolve-latest corpus (tnlr :ki "reason-is-fuzzy" "en")))]
      (is (= 1 (count ins)))
      (is (= "fuzzy-confidence" (:name (:tnlr (first ins)))))
      (is (= 1 (:minor (:doc (first ins)))) "resolves to the definition's latest minor")))
  (testing "publication-of resolves a member's link back to its publication (nil outside one)"
    (is (= "meta-graph"
           (:name (sut/publication-of corpus
                                      (sut/resolve-latest corpus
                                                          (tnlr :ki "reason-is-fuzzy" "en"))))))
    (is (nil? (sut/publication-of corpus
                                  (sut/resolve-latest corpus (tnlr :ki "fuzzy-confidence" "en"))))))
  (testing "members finds a publication's documents at their current version"
    (is (= #{"on-partial-knowledge" "reason-is-fuzzy"}
           (set (map :name
                     (sut/members corpus
                                  (sut/resolve-latest corpus
                                                      (tnlr :publication "meta-graph" "en"))))))))
  (testing "latest-lineages lists the current version of every distinct lineage"
    (is (contains? (set (map (juxt :type :name) (sut/latest-lineages corpus)))
                   [:ki "fuzzy-confidence"])))
  (testing "ref-issues over the corpus: a well-formed doc validates against its live lineages"
    (let [existing (into #{} (map (juxt :type :name :major)) corpus)]
      (is (empty? (:broken (lineage/ref-issues
                            (sut/resolve-latest corpus (tnlr :ki "reason-is-fuzzy" "en"))
                            existing)))))))
