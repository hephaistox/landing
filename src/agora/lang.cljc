(ns agora.lang
  "Langue.

## Définition

Une **langue** est la langue dans laquelle un texte est écrit. Elle se note par son code
ISO 639-1 sur deux lettres : `:fr`, `:en`.

## Buts

- Lire agora dans sa langue.
- Écrire la même pensée en plusieurs langues, sans en faire plusieurs pensées.

## Exemples

Le titre « Ma voiture est rapide » est en `:fr`. Le titre « My car is fast » est en `:en`.

## Liens

Aucun.")

(def langs "Languages, as ISO 639-1 keywords." #{:fr :en})

(def rules
  "Rules on a sequence of documents."
  [{:id :lang/known
    :description "un document et chaque citation par référence sont un langage connu."
    :check (fn [documents]
             (for [d documents
                   x (cons d (remove :id (:inputs d)))
                   :when (not (langs (:lang x)))]
               {:rule :lang/known
                :subject d
                :detail (:lang x)}))}])
