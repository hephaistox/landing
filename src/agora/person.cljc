(ns agora.person
  "Personne.

## Définition

Une **personne** est quelqu'un à qui l'on peut attribuer un écrit. Elle a un identifiant
et un nom d'affichage.

Une personne n'a pas forcément de compte. Victor Hugo est une personne d'agora : on lui
attribue une œuvre, mais il ne se connecte pas.

**Auteur** est un rôle d'une personne pour un document : celle à qui on l'attribue. Ce
n'est pas une sorte de personne.

Les personnes que les cas d'usage nomment, avec ou sans compte, sont dans
`agora/use_cases/personnes.edn`.

## Buts

- Dire **qui répond** d'un écrit.
- Créditer un **auteur public** qui n'utilise pas agora.
- Ne montrer qu'un nom d'affichage, jamais un identifiant.

## Exemples

```clojure
{:id \"61a3d5a4-a94a-4e02-9e1e-0db6f5c03503\" :name \"Hephaistox\"}   ; un compte
{:id \"6dee51b8-bd9b-4104-aefc-e082c0e1644c\" :name \"Victor hugo\"}  ; un auteur sans compte
{:id \"00000000-0000-0000-0000-000000000001\" :name \"Agora\"}        ; la plateforme elle-même
```

« Agora » est la personne qui tient les contenus de la plateforme : le vocabulaire des
types et l'exemple de la page d'accueil.

## Liens

Aucun : c'est le premier concept.")

(defn person?
  "A person is `{:id :name}`."
  [p]
  (and (map? p) (= #{:id :name} (set (keys p))) (string? (:id p)) (string? (:name p))))

(def rules
  "Rules on every person, with or without an account."
  [{:id :person/shape
    :description "une personne est {:id :name}"
    :check (fn [persons]
             (for [p persons
                   :when (not (person? p))]
               {:rule :person/shape
                :subject p}))}
   {:id :person/unique-id
    :description "un :id ne désigne qu'une personne"
    :check (fn [persons]
             (for [[id ps] (group-by :id persons)
                   :when (> (count ps) 1)]
               {:rule :person/unique-id
                :subject {:id id}
                :detail (map :name ps)}))}])
