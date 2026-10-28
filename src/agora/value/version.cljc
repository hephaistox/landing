(ns agora.value.version
  "Version and draft.

## Definition

A document evolves by **versions**. A version is noted `major.minor`, for instance `1.3`.

- The **major version** (`:major`) changes when the change is **breaking**: what relied on
  the previous version might no longer hold.
- The **minor version** (`:minor`) changes for a **non-breaking** change: making precise,
  correcting, completing.

A published version is **immutable**. To change a published document, one writes a new
version of it.

A **draft** is a version being written: a version **without a minor**: no `:minor` key. It
is edited in place. Publishing it gives it its minor and freezes it.

## Goals

- Improve a writing without breaking what cites it.
- Keep the history: every published version stays readable.
- Work on a text before committing to its content.

## Examples

The article `ma-voiture-est-rapide` has four minor versions. None breaks the previous one:
each adds an argument.

| version | text (fr)                         |
|---------|-----------------------------------|
| 1.0     | Ma voiture est rapide.            |
| 1.1     | + the definition of \"rapide\"    |
| 1.2     | + the measured 0-to-100           |
| 1.3     | + the generalization to the model |

The eight definitions and examples of \"La méthode scientifique\" are still drafts:

```clojure
{:name \"enonce-universel\" :major 1 :content {:title \"Énoncé universel\" …}}
```

## Links

- `agora.entity.document`: a version is a state of a document.
- `agora.value.name`: the versions of a document share its name."
  (:require
   [agora.value.type :as type]))

;; ---------------------------------------------------------------------------
;; Domain
;; ---------------------------------------------------------------------------

(def major-schema
  "The grammar of a major version."
  [:int {:min 1}])

(def minor-schema
  "The grammar of a minor version. A draft has none: no `:minor` key."
  [:int {:min 0}])

(defn draft? "A draft is a version without a minor." [document] (nil? (:minor document)))

(defn published? [document] (not (draft? document)))

;; ---------------------------------------------------------------------------
;; Rules
;; ---------------------------------------------------------------------------

(def rules
  "Rules on a sequence of documents."
  [{:id :version/minor
    :description "a minor is absent (draft: no :minor key) or an integer ≥ 0"
    :check (fn [documents]
             (for [d documents
                   :when (and (contains? d :minor) (not (nat-int? (:minor d))))]
               {:rule :version/minor
                :subject d
                :detail (:minor d)}))}
   {:id :version/described-types-published
    :description "type descriptions are published"
    :check (fn [documents]
             (for [d documents
                   :when (and (type/description? d) (draft? d))]
               {:rule :version/described-types-published
                :subject d}))}])
