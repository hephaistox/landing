(ns agora.value.name
  "Name.

## Definition

The **name** of a document is a stable string grouping all its successive states, in all
its languages. It does not change when the title changes.

The name is opaque: it is not shown as a title, and no rule ties it to the title. In
practice it is drawn from the title at creation, in lower case, without accents, words
joined by hyphens: \"Groupes et individus\" gives `groupes-et-individus`. If the title then
changes, the name stays, not to break what cites it, and no longer matches the title.

Type descriptions are the exception: they are always named `type-<type>`, for instance
`type-definition`, as they don't change.

## Goals

- Follow one thought over time, even when its title or its text change.
- Point at a document without depending on its title.
- Stay readable: a name says what the document is about.

## Examples

The four editions of the home page article, in French and in English, are all named
`ma-voiture-est-rapide`.

The definition \"Énoncé universel\" is named `enonce-universel`. The illustration with the
same title is named `enonce-universel` too: it is of another type.

*Fictional example*: if a new version of the article is titled \"Ma voiture est très
rapide\", its name stays `ma-voiture-est-rapide`. The citations already pointing at it stay
valid.

## Links

- `agora.entity.document`: every document has a name.
- `agora.value.type`: two documents of different types are two different documents, even with
  the same name.

`from-title` gives the name drawn from a title. It is not a rule: a name stays valid when
the title changes. If that name is already taken by another document of the same type and
language, the author picks another one, without changing the title: a competing definition
of \"Rapide\" keeps the title \"Rapide\" and is named `rapide-en-vitesse-de-pointe`."
  (:require
   [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Domain
;; ---------------------------------------------------------------------------

(def schema
  "The grammar of a name."
  [:string {:min 1}])

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
