(ns landing.agora.corpus-test
  "The `landing.agora.corpus` substrate (env/cli) — a corpus is a flat vector of versions. This
  tests the substrate-specific behaviour: the `create`/`edit` appends (and that they derive inputs
  from the text through `document.lineage`). Resolution and the graph queries are
  `document.lineage`'s (tested in `document.lineage-test`); the compact prints are
  `corpus-print`'s, asserted here over the worked example (`env/cli/agora/corpus-example.edn`)."
  (:require
   [clojure.edn                    :as edn]
   [clojure.java.io                :as io]
   [clojure.test                   :refer [deftest is testing]]
   [landing.agora.corpus           :as sut]
   [landing.agora.corpus-print     :as cprint]
   [landing.agora.document.lineage :as lineage]))

(def corpus (edn/read-string (slurp (io/resource "agora/corpus-example.edn"))))

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
                             {:type "ki"
                              :name "new-claim"
                              :lang "en"
                              :kind "inference"
                              :title "A new claim"
                              :author "Tester"
                              :text "this builds on [[ki:fuzzy-confidence:en@1]]."})]
      (is (= [{:type "ki"
               :name "fuzzy-confidence"
               :lang "en"
               :major 1}]
             (:inputs d))
          "inputs derived from the [[ki:…]] citation in the text")
      (is (= d (current c' (tnlr "ki" "new-claim" "en"))) "and it is now the current version")))
  (testing "edit appends a new minor; the original corpus is untouched (immutable)"
    (let [[_ d]
          (sut/edit corpus (tnlr "ki" "fuzzy-confidence" "en") {:title "Fuzzy confidence (v2)"})]
      (is (= 2 (:minor d)))
      (is (= "Fuzzy confidence (v2)" (:title d)))
      (is (= "Fuzzy confidence" (:title (current corpus (tnlr "ki" "fuzzy-confidence" "en"))))
          "the original corpus is unchanged")))
  (testing "editing the text re-derives :inputs (removing a citation drops the input)"
    (let [[_ d] (sut/edit corpus
                          (tnlr "ki" "reason-is-fuzzy" "en")
                          {:text "now standalone, with no citation."})]
      (is (= [] (:inputs d)))))
  (testing "editing an absent lineage is a no-op"
    (is (= [corpus nil] (sut/edit corpus (tnlr "ki" "ghost" "en") {:title "x"})))))

(deftest compact-print
  (testing "a document renders as one dense, stable line"
    (is (= "ki fuzzy-confidence@1.1 en [definition] \"Fuzzy confidence\""
           (cprint/compact-doc (current corpus (tnlr "ki" "fuzzy-confidence" "en")))))
    (is
     (=
      "ki reason-is-fuzzy@1.0 en [inference] \"Human reasoning is fuzzy\" ⇐ fuzzy-confidence@1 ∈ meta-graph (draft)"
      (cprint/compact-doc (current corpus (tnlr "ki" "reason-is-fuzzy" "en")))))
    (is
     (=
      "article on-partial-knowledge@1.0 en [explainer] \"On partial knowledge\" ⇐ fuzzy-confidence@1, reason-is-fuzzy@1 ∈ meta-graph (draft)"
      (cprint/compact-doc (current corpus (tnlr "article" "on-partial-knowledge" "en")))))
    (is (= "publication meta-graph@1.0 en <open> \"The meta-graph of Agora\" (draft)"
           (cprint/compact-doc (current corpus (tnlr "publication" "meta-graph" "en"))))))
  (testing "a publication renders with its members indented beneath it"
    (is
     (=
      (str
       "publication meta-graph@1.0 en <open> \"The meta-graph of Agora\" (draft) — 2 members\n"
       "  • article on-partial-knowledge@1.0 en [explainer] \"On partial knowledge\" ⇐ fuzzy-confidence@1, reason-is-fuzzy@1 ∈ meta-graph (draft)\n"
       "  • ki reason-is-fuzzy@1.0 en [inference] \"Human reasoning is fuzzy\" ⇐ fuzzy-confidence@1 ∈ meta-graph (draft)")
      (cprint/compact-publication corpus (current corpus (tnlr "publication" "meta-graph" "en"))))))
  (testing "the whole corpus lists every current lineage (documents, then publications expanded)"
    (let [dump (cprint/compact-corpus corpus)]
      (is (re-find #"ki fuzzy-confidence@1\.1 en" dump))
      (is (re-find #"ki fuzzy-confidence@1\.0 fr" dump))
      (is (re-find #"publication theology@1\.0 en <open>.*— 2 members" dump)))))
