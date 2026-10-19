(ns agora.identity
  "Identité et référence.

## Définition

L'**identité** d'une version de document est le quintuplet :

```
(type, nom, langue, majeure, mineure)
```

Deux versions ne peuvent pas avoir la même identité. Deux documents de types différents
peuvent donc porter le même nom : ce sont deux documents distincts. C'est permis, même si
c'est étrange en pratique.

Chaque version a aussi un **identifiant** (`:id`, un UUID), qui la désigne de façon
permanente. Un brouillon en a un, alors que son identité est incomplète : il n'a pas
encore de mineure.

Une **référence** est l'identité sans la mineure :

```
(type, nom, langue, majeure)
```

Elle désigne une **lignée** : toutes les mineures d'une même majeure. C'est ce qu'on cite.

## Buts

- Désigner un document sans ambiguïté.
- Rendre le type indissociable du document : changer de type, c'est changer d'identité.

## Exemples

Identité de la dernière édition de l'article de la page d'accueil, en français :

```
(:article, \"ma-voiture-est-rapide\", :fr, 1, 3)
```

Référence à la définition de « rapide », telle que les articles la citent :

```clojure
{:type :definition :name \"rapide\" :lang :fr :major 1}
```

## Liens

- `agora.type`, `agora.name`, `agora.lang`,
  `agora.version` : les parties de l'identité."
  (:require
   [agora.version :as version]))

(defn reference
  "`(type, name, lang, major)`: all the minors of one major."
  [document]
  (select-keys document [:type :name :lang :major]))

(defn identity-of
  "`(type, name, lang, major, minor)`."
  [document]
  ((juxt :type :name :lang :major :minor) document))

(defn next-minor
  "Minor `document` gets when published, after the latest published one of its major."
  [published-documents document]
  (let [minors (keep #(when (= (reference %) (reference document)) (:minor %)) published-documents)]
    (if (seq minors) (inc (apply max minors)) 0)))

(def rules
  "Rules on a sequence of documents."
  [{:id :identity/unique
    :description "identité unique parmi les versions publiées ; :id unique"
    :check (fn [documents]
             (concat (for [[k ds] (group-by identity-of (filter version/published? documents))
                           :when (> (count ds) 1)]
                       {:rule :identity/unique
                        :subject (first ds)
                        :detail k})
                     (for [[id ds] (group-by :id documents)
                           :when (> (count ds) 1)]
                       {:rule :identity/unique
                        :subject (first ds)
                        :detail id})))}
   {:id :identity/minors
    :description "les mineures publiées d'une majeure vont de 0 à n, sans trou"
    :check (fn [documents]
             (for [[r ds] (group-by reference (filter version/published? documents))
                   :let [minors (sort (map :minor ds))]
                   :when (not= minors (range (count minors)))]
               {:rule :identity/minors
                :subject r
                :detail minors}))}])

(def transition-rules
  "Rules on documents `before` and `after` a change."
  [{:id :identity/frozen
    :description "un document publié ne change plus"
    :check (fn [before after]
             (let [after-by-id (group-by :id after)]
               (for [d before
                     :when (and (version/published? d) (not= [d] (after-by-id (:id d))))]
                 {:rule :identity/frozen
                  :subject d})))}])
