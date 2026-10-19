(ns agora.name
  "Nom.

## Définition

Le **nom** d'un document est une chaîne stable qui regroupe tous ses états successifs,
dans toutes ses langues. Il ne change pas quand le titre change.

Le nom est opaque : on ne le montre pas comme un titre, et aucune règle ne le relie au
titre. En pratique, on le tire du titre à la création, en minuscules, sans accents, les
mots reliés par des tirets : « Groupes et individus » donne `groupes-et-individus`. Si le
titre change ensuite, le nom reste, pour ne pas casser ce qui le cite, et ne correspond
plus au titre.

Les descriptions de types font exception : elles s'appellent toujours `type-<type>`, par
exemple `type-definition`.

## Buts

- Suivre une même pensée dans le temps, même quand son titre ou son texte changent.
- Pointer vers un document sans dépendre de son titre.
- Rester lisible : un nom dit de quoi parle le document.

## Exemples

Les quatre éditions de l'article de la page d'accueil, en français et en anglais, portent
toutes le nom `ma-voiture-est-rapide`.

La définition « Énoncé universel » s'appelle `enonce-universel`. L'illustration du même
titre s'appelle aussi `enonce-universel` : elle est d'un autre type.

*Exemple fictif* : si une nouvelle version de l'article s'intitule « Ma voiture est très
rapide », son nom reste `ma-voiture-est-rapide`. Les citations qui pointent déjà vers lui
restent valables.

## Liens

- `agora.document` : tout document a un nom.
- `agora.type` : deux documents de types différents sont deux documents différents,
  même avec le même nom.

`from-title` donne le nom qu'on tire d'un titre. Ce n'est pas une règle : un nom reste
valable quand le titre change. Si ce nom est déjà pris par un autre document de même type
et de même langue, l'auteur en choisit un autre, sans changer son titre : une définition
concurrente de « Rapide » garde le titre « Rapide » et s'appelle
`rapide-en-vitesse-de-pointe`."
  (:require
   [clojure.string :as str]))

(defn- strip-accents
  [s]
  #?(:clj (str/replace (java.text.Normalizer/normalize s java.text.Normalizer$Form/NFD) #"\p{M}" "")
     :cljs (str/replace (.normalize s "NFD") #"[\u0300-\u036f]" "")))

(defn from-title
  "Name drawn from `title`: lower case, without accents, words joined by hyphens.
  « Groupes et individus » gives `groupes-et-individus`."
  [title]
  (-> title
      str/lower-case
      (str/replace "œ" "oe")
      (str/replace "æ" "ae")
      strip-accents
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-|-$" "")))
