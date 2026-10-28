(ns agora.entity.document
  "Document.

## Definition

A **document** is the unit of writing of agora. Everything written in agora is a document:
a definition, an article, a cited work, a publication.

A document has, apart from its identity:

- its **content**, what it says (`:content`): a **title**, most of the time a **text**, its
  citations and its **publication date**. Once published, the content never changes;
- a **language**;
- an **owner**: the user who answers for it, designated by its `:id`: who wrote it, or the
  account holding the contents of the platform, \"Agora\";
- for a work, an **author**: the person it is credited to, with or without an account;
- a **timestamp**, when its owner proved it existed at a date (`agora.value.timestamp`).

The publication date (`:published-at`) is an EDN `#inst`, in UTC.

Its other attributes are described by `agora.value.type`, `agora.value.name`, `agora.value.version` and
`agora.value.identity`.

## Goals

- One single concept for everything written. The same rules hold everywhere: versions,
  languages, citations.
- Always know who answers for what is written.

## Examples

The smallest document of the home page example:

```clojure
{:lang :fr
 :content {:title \"Ma voiture est rapide\"
           :text \"Ma voiture est rapide.\"
           :inputs []
           :published-at #inst \"2026-08-28T12:39:01Z\"}}
```

Its owner is \"Agora\".

## Links

- `agora.entity.person`: the owner is a person.
- `agora.entity.user`: a user writes the document.
- `agora.value.lang`: every document is in one language."
  (:require
   [agora.value.citation  :as citation]
   [agora.value.identity  :as identity]
   [agora.value.reference :as reference]
   [agora.value.timestamp :as timestamp]
   [auto-core.schema      :as schema]))

;; ---------------------------------------------------------------------------
;; Domain
;; ---------------------------------------------------------------------------

(def published-at-schema "A UTC instant: an EDN `#inst`." inst?)

(def content-schema
  "The grammar of what a document says: its title, its text, what it cites and its date.
  `:editor`, `:year` (an integer: an edition is often known only by its year), `:url`
  describe a work, `:locator` an extract."
  [:map {:closed true}
   [:title [:string {:min 1}]]
   [:text [:maybe :string]]
   [:editor {:optional true}
    :string]
   [:year {:optional true}
    :int]
   [:url {:optional true}
    :string]
   [:locator {:optional true}
    :string]
   [:inputs [:vector citation/schema]]
   [:published-at published-at-schema]])

(def schema
  "The grammar of a document: its `:id`, its identity, who answers for it and who it is
  credited to, what it says (`:content`), and its timestamp."
  (-> [:map {:closed true}
       [:id :uuid]]
      (into (drop 2 identity/schema))
      (into [[:owner reference/user-schema]
             [:author {:optional true}
              reference/person-schema]
             [:content content-schema]
             [:timestamp {:optional true}
              timestamp/schema]])))

(defn validate
  "nil when `document` follows `schema`, else a structured error `{:error :schema :data}`."
  [document]
  (schema/validate-data-humanize schema document))

;; ---------------------------------------------------------------------------
;; Rules
;; ---------------------------------------------------------------------------

(def rules
  "Rules on a sequence of documents."
  [{:id :document/shape
    :description
    "a document follows its grammar: id, identity, owner, title, text, citations and publication date"
    :check (fn [documents]
             (for [d documents
                   :let [error (validate d)]
                   :when error]
               {:rule :document/shape
                :subject d
                :detail (:error error)}))}])
