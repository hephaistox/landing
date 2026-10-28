(ns agora.value.reference
  "Reference.

## Definition

A **reference** designates something. Never used alone: its kind says what it designates,
and how.

- **By id** (`{:id …}`): one entity precisely, a person, a user, a publication, an
  objection, a version of a document. It holds nothing else: the entity itself, its name
  included, is read from the repository, never copied.
- **By tnlr** (`{:type :name :lang :major}`): a lineage, every minor of one major of a
  document. That is how a published version is cited.

## Goals

- Designate without copying: a change of the entity never leaves a stale copy.
- Say which kind of entity a designation expects.
- Let the entities designate each other without depending on each other.
- Cite a lineage, so a reader gets its latest minor.

## Examples

```clojure
{:id #uuid \"61a3d5a4-a94a-4e02-9e1e-0db6f5c03503\"}   ; Hephaistox, a user, as an owner
{:id #uuid \"d6f0a3c2-6f1b-4c41-9a51-1f3e0c2a7b11\"}   ; an objection a publication answers
{:type :definition :name \"rapide\" :lang :fr :major 1}  ; the definition of \"rapide\", cited
```

## Links

- `agora.value.identity`: the tnlr is the identity without the minor."
  (:require
   [agora.value.lang    :as lang]
   [agora.value.name    :as name]
   [agora.value.type    :as type]
   [agora.value.version :as version]))

;; ---------------------------------------------------------------------------
;; Domain
;; ---------------------------------------------------------------------------

(def ^:private by-id
  [:map {:closed true}
   [:id :uuid]])

(def person-schema "The grammar of a person designated, e.g. the author of a work." by-id)

(def user-schema "The grammar of a user designated: an owner or a contributor." by-id)

(def publication-schema "The grammar of a publication designated." by-id)

(def objection-schema
  "The grammar of an objection designated, e.g. one a publication answers."
  by-id)

(def version-schema "The grammar of a version of a document designated." by-id)

(def timestamp-schema "The grammar of a timestamp designated." by-id)

(def tnlr-schema
  "The grammar of a lineage designated by its tnlr: `(type, name, lang, major)`."
  [:map {:closed true}
   [:type type/schema]
   [:name name/schema]
   [:lang lang/schema]
   [:major version/major-schema]])

