(ns agora.document
  "Document.

## Définition

Un **document** est l'unité d'écriture d'agora. Tout ce qu'on écrit dans agora est un
document : une définition, un article, une œuvre citée, une publication.

Un document a :

- un **titre** et, le plus souvent, un **texte** ;
- une **langue** ;
- un **propriétaire** : la personne qui en répond. C'est en général l'utilisateur qui l'a
  écrit. Ce peut être une personne sans compte, comme « Agora » ;
- une **date de publication** (`:published-at`, en UTC).

Ses autres attributs sont décrits par `agora.type`, `agora.name`, `agora.version` et
`agora.identity`.

## Buts

- Un seul concept pour tout ce qui s'écrit. Les mêmes règles valent partout : versions,
  langues, citations.
- Savoir toujours qui répond de ce qui est écrit.

## Exemples

Le plus petit document de l'exemple de la page d'accueil :

```clojure
{:lang :fr
 :title \"Ma voiture est rapide\"
 :text \"Ma voiture est rapide.\"
 :published-at \"2026-08-28T12:39:01Z\"}
```

Son propriétaire est « Agora ».

## Liens

- `agora.person` : le propriétaire est une personne.
- `agora.user` : c'est un utilisateur qui écrit le document.
- `agora.lang` : chaque document est dans une langue."
  (:require
   [clojure.string :as str]))

(def rules
  "Rules on a sequence of documents."
  [{:id :document/title-and-date
    :description "un document a un titre et une date de publication"
    :check (fn [documents]
             (for [d documents
                   :when (or (str/blank? (:title d)) (nil? (:published-at d)))]
               {:rule :document/title-and-date
                :subject d}))}])
