(ns agora.entity.person
  "Person.

## Definition

A **person** is someone a writing can be attributed to. A person has an id and a display
name.

A person does not necessarily have an account. Victor Hugo is a person of agora: a work is
attributed to him, but he never logs in.

**Author** is a role of a person for a document: the one it is attributed to. It is not a
kind of person.

Elsewhere, a person is designated by its `:id` only, e.g. as the author of a work: see
`agora.value.reference`. An owner or a contributor is a user. Its name is read from the persons, never copied.

The persons the use cases name, with or without an account, are in
`agora/use_cases/persons.edn`.

## Goals

- Say **who answers** for a writing.
- Credit a **public author** who does not use agora.
- Only ever show a display name, never an id.

## Examples

```clojure
{:id #uuid \"61a3d5a4-a94a-4e02-9e1e-0db6f5c03503\" :name \"Hephaistox\"}   ; an account
{:id #uuid \"6dee51b8-bd9b-4104-aefc-e082c0e1644c\" :name \"Victor hugo\"}  ; an author without an account
{:id #uuid \"00000000-0000-0000-0000-000000000001\" :name \"Agora\"}        ; the platform itself, an account
```

\"Agora\" is the user who holds the contents of the platform: the vocabulary of types and
the example of the home page.

## Links

None: this is the first concept."
  (:require
   [auto-core.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Domain
;; ---------------------------------------------------------------------------

(def schema
  "The grammar of a person."
  [:map {:closed true}
   [:id :uuid]
   [:name [:string {:min 1}]]])

(defn validate
  "nil when `p` is a person, else a structured error `{:error :schema :data}`."
  [p]
  (schema/validate-data-humanize schema p))

(defn person? [p] (nil? (validate p)))

;; ---------------------------------------------------------------------------
;; Rules
;; ---------------------------------------------------------------------------

(def rules
  "Rules on every person, with or without an account."
  [{:id :person/shape
    :description "a person is {:id :name}"
    :check (fn [persons]
             (for [p persons
                   :let [error (validate p)]
                   :when error]
               {:rule :person/shape
                :subject p
                :detail (:error error)}))}
   {:id :person/unique-id
    :description "an :id designates one person only"
    :check (fn [persons]
             (for [[id ps] (group-by :id persons)
                   :when (> (count ps) 1)]
               {:rule :person/unique-id
                :subject {:id id}
                :detail (map :name ps)}))}])
