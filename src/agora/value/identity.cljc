(ns agora.value.identity
  "Identity and tnlr.

## Definition

The **identity** of a document version is the quintuple:

```
(type, name, language, major, minor)
```

Two versions cannot have the same identity. Two documents of different types may hence
share a name: they are two distinct documents. It is allowed, even if odd in practice.

Every version also has an **id** (`:id`, a UUID) standing for it permanently. A draft has
one, while its identity is incomplete: it has no minor yet.

The **tnlr** is the identity without the minor:

```
(type, name, language, major)
```

It stands for a **lineage**: all the minors of one major. That is what is cited.

## Goals

- Designate a document without ambiguity.
- Make the type inseparable from the document: changing type means changing identity.

## Examples

Identity of the last edition of the home page article, in French:

```
  [:article, \"ma-voiture-est-rapide\", :fr, 1, 3]
```

The tnlr of the definition of \"rapide\", as the articles cite it:

```clojure
{:type :definition :name \"rapide\" :lang :fr :major 1}
```

## Links

- `agora.value.type`, `agora.value.name`, `agora.value.lang`, `agora.value.version`: the parts of the identity.
- `agora.value.reference`: the grammar of a tnlr."
  (:require
   [agora.value.reference :as reference]
   [agora.value.version   :as version]
   [auto-core.schema      :as schema]))

;; ---------------------------------------------------------------------------
;; Domain
;; ---------------------------------------------------------------------------

(def schema
  "The grammar of an identity: a tnlr and its minor."
  (conj reference/tnlr-schema
        [:minor {:optional true}
         version/minor-schema]))

(defn validate-tnlr
  "nil when `tnlr` follows `reference/tnlr-schema`, else a structured error `{:error :schema :data}`."
  [tnlr]
  (schema/validate-data-humanize reference/tnlr-schema tnlr))

(defn validate
  "nil when `identity` follows `schema`, else a structured error `{:error :schema :data}`."
  [identity]
  (schema/validate-data-humanize schema identity))

(defn tnlr
  "`(type, name, lang, major)`: all the minors of one major."
  [document]
  (select-keys document [:type :name :lang :major]))

(defn identity-of
  "`(type, name, lang, major, minor)`."
  [document]
  ((juxt :type :name :lang :major :minor) document))

(defn latest-minor
  "The version `citation` reads among `documents`: the published version of its type, name,
  language and major with the highest minor. nil when none is published. `documents` may
  hold any version of any document."
  [documents citation]
  (let [versions (filter #(and (version/published? %) (= (tnlr citation) (tnlr %))) documents)]
    (when (seq versions) (apply max-key :minor versions))))

(defn next-minor
  "Minor `document` gets when published, after the latest published one of its major."
  [documents document]
  (if-let [latest (latest-minor documents document)]
    (inc (:minor latest))
    0))

;; ---------------------------------------------------------------------------
;; Rules
;; ---------------------------------------------------------------------------

(def rules
  "Rules on a sequence of documents."
  [{:id :identity/unique
    :description "the identity is unique among published versions; the :id is unique"
    :check (fn [documents]
             (concat (for [[k ds] (group-by identity-of (filter version/published? documents))
                           :when (> (count ds) 1)]
                       {:rule :identity/unique
                        :subject (first ds)
                        :detail k})
                     (for [[id ds] (group-by :id documents)
                           :when (> (count ds) 1)]
                       {:rule :identity/unique
                        :subject (first ds)
                        :detail id})))}
   {:id :identity/minors
    :description "the published minors of a major go from 0 to n, without gap"
    :check (fn [documents]
             (for [[r ds] (group-by tnlr (filter version/published? documents))
                   :let [minors (sort (map :minor ds))]
                   :when (not= minors (range (count minors)))]
               {:rule :identity/minors
                :subject r
                :detail minors}))}])
