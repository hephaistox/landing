(ns agora.version
  "Version et brouillon.

## Définition

Un document évolue par **versions**. Une version se note `majeure.mineure`, par exemple `1.3`.

- La **version majeure** (`:major`) change quand la modification est **cassante** : ce qui
  s'appuyait sur l'ancienne version pourrait ne plus tenir.
- La **version mineure** (`:minor`) change pour une modification **non cassante** : préciser,
  corriger, compléter.

Une version publiée est **immuable**. Pour changer un document publié, on en écrit une
nouvelle version.

Un **brouillon** est une version en cours d'écriture : une version **sans mineure**. Il se modifie sur
place et n'a pas encore de version mineure (`:minor nil`). Le publier lui attribue sa
mineure et le fige.

## Buts

- Pouvoir améliorer un écrit sans casser ce qui le cite.
- Garder l'historique : chaque version publiée reste lisible.
- Travailler un texte avant de s'engager sur son contenu.

## Exemples

L'article `ma-voiture-est-rapide` a quatre versions mineures. Aucune ne casse la précédente :
chacune ajoute un argument.

| version | texte (fr)                    |
|---------|-------------------------------|
| 1.0     | Ma voiture est rapide.        |
| 1.1     | + la définition de « rapide » |
| 1.2     | + la mesure du 0 à 100        |
| 1.3     | + la généralisation au modèle |

Les huit définitions et exemples de « La méthode scientifique » sont encore des brouillons :

```clojure
{:name \"enonce-universel\" :major 1 :minor nil :title \"Énoncé universel\" …}
```

## Liens

- `agora.document` : une version est un état d'un document.
- `agora.name` : les versions d'un document partagent son nom."
  (:require
   [agora.type :as type]))

(defn draft? "A draft is a version without a minor." [document] (nil? (:minor document)))

(defn published? [document] (not (draft? document)))

(def rules
  "Rules on a sequence of documents."
  [{:id :version/minor
    :description "une mineure est absente (brouillon) ou entière ≥ 0"
    :check (fn [documents]
             (for [d documents
                   :when (not (or (nil? (:minor d)) (nat-int? (:minor d))))]
               {:rule :version/minor
                :subject d
                :detail (:minor d)}))}
   {:id :version/described-types-published
    :description "les descriptions de types sont publiées"
    :check (fn [documents]
             (for [d documents
                   :when (and (type/description? d) (draft? d))]
               {:rule :version/described-types-published
                :subject d}))}])
