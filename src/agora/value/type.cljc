(ns agora.value.type
  "Type.

## Definition

The **type** of a document says what it is, hence how it is read and how it is discussed.
It is taken from a closed list:

| family     | types                                                                                                                                       |
|------------|---------------------------------------------------------------------------------------------------------------------------------------------|
| grouping   | `:publication`                                                                                                                              |
| statements | `:definition`, `:assumption`, `:belief`, `:measurable-fact`, `:prediction`, `:deduction`, `:induction`, `:illustration`, `:counter-example` |
| explaining | `:article`                                                                                                                                  |
| provenance | `:work`, `:extract`                                                                                                                         |
| contesting | `:objection`                                                                                                                                |

Each type is described in agora itself, by a document of type `:definition` named
`type-<type>`: `type-definition`, `type-article`, `type-work`… That document gives the
meaning of the type, the phrase that opens its text and the way to contest it. The badge
showing a document's type links to it.

Each type is also defined as data, in `definitions`: its family, what it cites
(`:inputs`), the phrase that opens its text (`:opening`), the default type of a document
built on it (`:consequence`) and the **objections it predefines**. A type can be objected,
and each type says how: a definition is never contradicted, but it can be completed or
competed with; a general claim is objected by a counter-example; a measure by its protocol
or a contradicting measure. Each objection says which types of documents may back it
(`:backed-by`). A publication and an objection predefine none: they are not contested.

**The type is part of the document's identity.** It never changes. Turning a deduction
into a definition means creating a new document of type `:definition`. The interface may
make that easy, but it is another document.

## Goals

- Give every document a **reading rule** and a **contesting rule**. A measure is not
  contested like a deduction.
- Prevent drifts of meaning: a publication never becomes an article.
- Define the opening phrase that starts the text of the document, according to its reading
  rule.
- Let the reader always know what a type means: its description follows the same rules as
  any document; it is versioned, cited and discussed.

## Examples

Three documents of different types:

```clojure
{:type :definition :content {:title \"Rapide\" …}}
{:type :article    :content {:title \"Ma voiture est rapide\" …}}
{:type :work       :content {:title \"Les misérables\" …}}
```

The type `:definition` is described by the document `type-definition`:

```clojure
{:type :definition
 :name \"type-definition\"
 :lang :fr
 :major 1
 :minor 0
 :content {:title \"Définition\"
           :text \"un contrat sémantique qui fixe le sens d'un terme dans le graphe ; les termes clés des autres énoncés y renvoient.\\n\\nUne définition ne se conteste pas en elle-même : on lui oppose une autre définition du même mot. Le graphe fait alors apparaître l'ambiguïté — plusieurs définitions concurrentes pour un terme — au lieu de trancher.\" …}}
```

- Its text completes the phrase \"Définition signifie …\" (\"Definition means …\").
- Its first paragraph gives the meaning of the type.
- Its second paragraph says how to contest a document of that type.

That document is itself of type `:definition`: it describes itself, and its badge links to
it. Every other type description is a definition too, and its badge links to
`type-definition`.

The definition \"Rapide\" is of that type. Its badge links to that description:

```
┌──────────────────────────────────────────────────┐
│ [Définition] ──► type-definition                 │
│ Rapide                                           │
│ atteindre 100 km/h en moins de 5 secondes.       │
└──────────────────────────────────────────────────┘
```

So \"Rapide\" is not contested in itself: one writes another definition of \"rapide\".

## Links

- `agora.entity.document`: every document has exactly one type."
  (:require
   [agora.value.lang :as lang]
   [clojure.string   :as str]))

;; ---------------------------------------------------------------------------
;; Domain
;; ---------------------------------------------------------------------------

(def definitions
  "Every type, defined. A definition says:

  - `:family`: `:grouping`, `:statement`, `:explaining`, `:provenance` or `:contesting`;
  - `:inputs`: what the document cites: `:none`, `:optional`, `:required` (it stands on
    another claim) or `:one-work` (the single work an extract comes from);
  - `:opening`: the phrase that opens its text, `{:subject :author|:term|:target, :phrase
    {lang …}}`; absent when the text is free;
  - `:consequence`: the default type of a document built on it;
  - `:objections`: the objections the type predefines, each
    `{:id :label {lang …} :backed-by #{types}}`, `:backed-by` being the types of the documents
    that may support it. An empty list: the type is not contested;
  - `:timestamp?`: true when a published version of it may be timestamped, to prove it
    existed at a date. Absent: it may not."
  [{:type :publication
    :family :grouping
    :inputs :none
    :objections []}
   {:type :definition
    :family :statement
    :inputs :optional
    :opening {:subject :term
              :phrase {:fr "signifie"
                       :en "means"}}
    :objections [{:id :incomplete
                  :label {:fr "La définition est incomplète"
                          :en "The definition is incomplete"}
                  :backed-by #{:illustration :counter-example}}
                 {:id :competing
                  :label {:fr "Une autre définition du même mot"
                          :en "Another definition of the same word"}
                  :backed-by #{:definition}}]}
   {:type :assumption
    :family :statement
    :inputs :optional
    :opening {:subject :author
              :phrase {:fr "suppose que"
                       :en "assumes that"}}
    :objections [{:id :too-strong
                  :label {:fr "La supposition est trop forte"
                          :en "The assumption is too strong"}
                  :backed-by #{:assumption}}]}
   {:type :belief
    :family :statement
    :inputs :optional
    :opening {:subject :author
              :phrase {:fr "croit que"
                       :en "believes that"}}
    :objections [{:id :incompatible
                  :label {:fr "Une croyance incompatible"
                          :en "An incompatible belief"}
                  :backed-by #{:belief}}
                 {:id :counter-example
                  :label {:fr "Un contre-exemple : s'il est vrai, ce n'est pas une croyance"
                          :en "A counter-example: if true, it is not a belief"}
                  :backed-by #{:counter-example}}]}
   {:type :measurable-fact
    :family :statement
    :inputs :optional
    :objections [{:id :protocol
                  :label {:fr "Le protocole de mesure"
                          :en "The measuring protocol"}
                  :backed-by #{:measurable-fact :work :extract}}
                 {:id :contradicting-measure
                  :label {:fr "Une mesure qui le contredit"
                          :en "A measure contradicting it"}
                  :backed-by #{:measurable-fact}}]}
   {:type :prediction
    :family :statement
    :inputs :optional
    :timestamp? true
    :opening {:subject :author
              :phrase {:fr "prédit que"
                       :en "predicts that"}}
    :objections [{:id :reasoning
                  :label {:fr "Le raisonnement ne tient pas"
                          :en "The reasoning does not hold"}
                  :backed-by #{:deduction :induction :counter-example}}
                 {:id :settled
                  :label {:fr "L'événement l'a tranchée"
                          :en "The event settled it"}
                  :backed-by #{:measurable-fact}}]}
   {:type :deduction
    :family :statement
    :inputs :required
    :objections [{:id :invalid-form
                  :label {:fr "La forme n'est pas valide"
                          :en "The form is not valid"}
                  :backed-by #{:counter-example}}
                 {:id :equivocation
                  :label {:fr "Un terme est équivoque"
                          :en "A term is equivocal"}
                  :backed-by #{:definition}}]}
   {:type :induction
    :family :statement
    :inputs :required
    :objections [{:id :leap
                  :label {:fr "Le saut est trop large"
                          :en "The leap is too wide"}
                  :backed-by #{:measurable-fact}}
                 {:id :counter-example
                  :label {:fr "Un contre-exemple"
                          :en "A counter-example"}
                  :backed-by #{:counter-example}}]}
   {:type :illustration
    :family :statement
    :inputs :required
    :opening {:subject :target
              :phrase {:fr "illustre"
                       :en "illustrates"}}
    :objections [{:id :irrelevant
                  :label {:fr "L'exemple n'illustre pas l'affirmation"
                          :en "The example does not illustrate the claim"}
                  :backed-by #{}}
                 {:id :misleading
                  :label {:fr "L'exemple est trompeur"
                          :en "The example is misleading"}
                  :backed-by #{:counter-example}}]}
   {:type :counter-example
    :family :statement
    :inputs :required
    :opening {:subject :target
              :phrase {:fr "réfute"
                       :en "refutes"}}
    :objections [{:id :not-real
                  :label {:fr "Le cas n'est pas réel"
                          :en "The case is not real"}
                  :backed-by #{:measurable-fact :extract}}
                 {:id :out-of-scope
                  :label {:fr "Le cas ne relève pas de l'affirmation"
                          :en "The case is out of the claim's scope"}
                  :backed-by #{:definition}}]}
   {:type :article
    :family :explaining
    :inputs :optional
    :objections [{:id :unclear
                  :label {:fr "L'article n'est pas clair"
                          :en "The article is unclear"}
                  :backed-by #{}}
                 {:id :unbalanced
                  :label {:fr "L'article n'est pas équilibré"
                          :en "The article is unbalanced"}
                  :backed-by #{:article}}
                 {:id :omission
                  :label {:fr "L'article omet un appui"
                          :en "The article leaves out a support"}
                  :backed-by #{:definition :assumption :belief :measurable-fact :prediction
                               :deduction :induction :illustration :counter-example :extract}}]}
   {:type :work
    :family :provenance
    :inputs :none
    :consequence :extract
    :objections [{:id :inexact
                  :label {:fr "La référence est inexacte"
                          :en "The reference is inexact"}
                  :backed-by #{:work}}
                 {:id :attribution
                  :label {:fr "L'attribution est fausse"
                          :en "The attribution is wrong"}
                  :backed-by #{:work :extract}}]}
   {:type :extract
    :family :provenance
    :inputs :one-work
    :opening {:subject :author
              :phrase {:fr "écrit"
                       :en "writes"}}
    :consequence :definition
    :objections [{:id :unfaithful
                  :label {:fr "Le passage n'est pas fidèle"
                          :en "The passage is not faithful"}
                  :backed-by #{:extract}}
                 {:id :misplaced
                  :label {:fr "L'emplacement est faux"
                          :en "The location is wrong"}
                  :backed-by #{}}]}
   {:type :objection
    :family :contesting
    :inputs :none
    :objections []}])

(def types "The closed list of types." (set (map :type definitions)))

(def schema "The grammar of a type." (into [:enum] (sort types)))

(defn definition-of
  "The definition of `type`."
  [type]
  (some #(when (= type (:type %)) %) definitions))

(defn objection-of
  "The objection `id` that `type` predefines, or nil."
  [type id]
  (some #(when (= id (:id %)) %) (:objections (definition-of type))))

(defn timestamp?
  "May a published version of `type` be timestamped?"
  [type]
  (boolean (:timestamp? (definition-of type))))

(defn consequence-of
  "The default type of a document built on one of `type`: a `:deduction` unless it says
  otherwise."
  [type]
  (:consequence (definition-of type) :deduction))

(defn description-name
  "Name of the definition describing `type`: `type-<type>`."
  [type]
  (str "type-" (name type)))

(defn description?
  "Does `document` describe a type?"
  [document]
  (str/starts-with? (str (:name document)) "type-"))

;; ---------------------------------------------------------------------------
;; Rules
;; ---------------------------------------------------------------------------

(def rules
  "Rules on a sequence of documents."
  [{:id :type/known
    :description "the type is taken from the closed list; no more :kind"
    :check (fn [documents]
             (for [d documents
                   :when (or (not (types (:type d))) (contains? d :kind))]
               {:rule :type/known
                :subject d
                :detail (:type d)}))}
   {:id :type/described
    :description
    "every type a document uses is described by a type-<type> definition, in the document's language"
    :check (fn [documents]
             (concat (for [[t l] (distinct (map (juxt :type :lang) documents))
                           :when (and (types t) (lang/langs l))
                           :when (not (some #(and (= (description-name t) (:name %))
                                                  (= l (:lang %))
                                                  (= :definition (:type %)))
                                            documents))]
                       {:rule :type/described
                        :subject {:type t
                                  :lang l}})
                     (for [d documents
                           :when (and (description? d) (not (types (keyword (subs (:name d) 5)))))]
                       {:rule :type/described
                        :subject d
                        :detail :unknown-type})))}
   {:id :type/text
    :description "a work has a credited author; any other document has a text"
    :check (fn [documents]
             (for [d documents
                   :when (if (= :work (:type d))
                           (nil? (:author d))
                           (nil? (-> d
                                     :content
                                     :text)))]
               {:rule :type/text
                :subject d}))}])
