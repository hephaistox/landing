(ns agora.type
  "Type.

## Définition

Le **type** d'un document dit ce qu'il est, et donc comment on le lit et comment on le
discute. Il est pris dans une liste fermée :

| famille      | types                                                                                                                                       |
|--------------|---------------------------------------------------------------------------------------------------------------------------------------------|
| regroupement | `:publication`                                                                                                                              |
| énoncés      | `:definition`, `:assumption`, `:belief`, `:measurable-fact`, `:prediction`, `:deduction`, `:induction`, `:illustration`, `:counter-example` |
| explication  | `:article`                                                                                                                                  |
| provenance   | `:work`, `:extract`                                                                                                                         |

Chaque type est décrit dans agora même, par un document de type `:definition` nommé
`type-<type>` : `type-definition`, `type-article`, `type-work`… Ce document dit le sens du
type, la phrase qui introduit son texte et la manière de le contester. Le badge qui affiche
le type d'un document renvoie à lui.

**Le type fait partie de l'identité du document.** Il ne change jamais. Transformer une
déduction en définition, c'est créer un nouveau document de type `:definition`. L'interface
peut rendre ce passage facile, mais c'est un autre document.

## Buts

- Donner à chaque document une **règle de lecture** et une **règle de contestation**. On ne
  conteste pas une mesure comme une déduction.
- Empêcher les glissements de sens : une publication ne devient jamais un article.
- Définir la phrase d'introduction qui démarre le texte du document, en fonction de la règle de lecture
- Que le lecteur puisse toujours savoir ce que veut dire un type : sa description suit les
  mêmes règles que tout document, elle se versionne, se cite et se discute.

## Exemples

Trois documents de types différents :

```clojure
{:type :definition :title \"Rapide\" …}
{:type :article    :title \"Ma voiture est rapide\" …}
{:type :work       :title \"Les misérables\" …}
```

Le type `:definition` est décrit par le document `type-definition` :

```clojure
{:type :definition 
 :name \"type-definition\" 
 :lang :fr 
 :major 1 
 :minor 0
 :title \"Définition\"
 :text \"un contrat sémantique qui fixe le sens d'un terme dans le graphe ; les termes clés des autres énoncés y renvoient.\\n\\nUne définition ne se conteste pas en elle-même : on lui oppose une autre définition du même mot. Le graphe fait alors apparaître l'ambiguïté — plusieurs définitions concurrentes pour un terme — au lieu de trancher.\"}
```

- Son texte complète la phrase « Définition signifie … ».
- Son premier paragraphe donne le sens du type.
- Son second paragraphe dit comment contester un document de ce type.

Ce document est lui-même de type `:definition` : il se décrit lui-même, et son badge renvoie
à lui. Toutes les autres descriptions de types sont aussi des définitions, et leur badge
renvoie à `type-definition`.

La définition « Rapide » est de ce type. Son badge renvoie à cette description :

```
┌──────────────────────────────────────────────────┐
│ [Définition] ──► type-definition                 │
│ Rapide                                           │
│ atteindre 100 km/h en moins de 5 secondes.       │
└──────────────────────────────────────────────────┘
```

On ne conteste donc pas « Rapide » en elle-même : on écrit une autre définition de
« rapide ».

## Liens

- `agora.document` : tout document a exactement un type."
  (:require
   [agora.lang     :as lang]
   [clojure.string :as str]))

(def types
  "The closed list of types."
  #{:publication :definition :assumption :belief :measurable-fact :prediction :deduction :induction
    :illustration :counter-example :article :work :extract})

(defn description-name
  "Name of the definition describing `type`: `type-<type>`."
  [type]
  (str "type-" (name type)))

(defn description?
  "Does `document` describe a type?"
  [document]
  (str/starts-with? (:name document) "type-"))

(def rules
  "Rules on a sequence of documents."
  [{:id :type/known
    :description "le type est pris dans la liste fermée ; plus de :kind"
    :check (fn [documents]
             (for [d documents
                   :when (or (not (types (:type d))) (contains? d :kind))]
               {:rule :type/known
                :subject d
                :detail (:type d)}))}
   {:id :type/described
    :description "chaque type est décrit par une définition type-<type>, en fr et en en"
    :check (fn [documents]
             (concat (for [t types
                           l lang/langs
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
    :description "une œuvre a un auteur crédité ; tout autre document a un texte"
    :check (fn [documents]
             (for [d documents
                   :when (if (= :work (:type d)) (nil? (:author d)) (nil? (:text d)))]
               {:rule :type/text
                :subject d}))}])
