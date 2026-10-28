(ns agora.repository
  "Repository.

## Definition

The **repository** is the whole state of agora: its persons, its users, its publications
and its objections.

```clojure
{:persons [] :users [] :publications [] :objections []}
```

Every action takes it and returns its **changes**: per kind, the entities it creates and
the entities it changes, each whole.

```clojure
{:created {:persons [...] :users [...]} :changed {:publications [...]}}
```

`apply-changes` applies them: a created entity is added, a changed one replaces the
entity with the same `:id`. It never changes the repository in place.

## Goals

- Give the actions a single entry and exit point.
- One generic way to apply what any action does.
- Check at once every rule of every concept (`violations`).

## Examples

The empty repository, before anyone signs up: `empty-repository`. A sign-up returns
`{:created {:persons [anthony] :users [anthony-account]}}`.

## Links

- `agora.entity.person`, `agora.entity.user`, `agora.entity.publication`, `agora.entity.objection`: what it holds."
  (:require
   [agora.entity.objection :as objection]
   [auto-core.schema       :as schema]))

;; ---------------------------------------------------------------------------
;; Domain
;; ---------------------------------------------------------------------------

(def empty-repository
  "Agora before anything happens."
  {:persons []
   :users []
   :publications []
   :objections []})

(def kinds-schema
  "Entities per kind of the repository, each kind a vector."
  (into [:map {:closed true}]
        (for [kind (keys empty-repository)]
          [kind {:optional true}
           [:vector [:map [:id :uuid]]]])))

(def changes-schema
  "The grammar of changes: the entities created, and the entities changed."
  [:map {:closed true}
   [:created {:optional true}
    kinds-schema]
   [:changed {:optional true}
    kinds-schema]])

(defn apply-changes
  "The repository after `changes`. A created entity is added; a changed one replaces the
  entity with the same `:id`. Throws when `changes` breaks its grammar, creates an `:id`
  already there, or changes a missing one."
  [repository
   {:keys [created changed]
    :as changes}]
  (when-let [invalid (schema/validate-data-humanize changes-schema changes)]
    (throw (ex-info "Invalid changes" invalid)))
  (let [ids (fn [r kind] (set (map :id (get r kind))))
        create (fn [r [kind xs]]
                 (when-let [taken (seq (filter (comp (ids r kind) :id) xs))]
                   (throw (ex-info "Created ids already there"
                                   {:kind kind
                                    :ids (mapv :id taken)})))
                 (update r kind (fnil into []) xs))
        change (fn [r [kind xs]]
                 (when-let [missing (seq (remove (comp (ids r kind) :id) xs))]
                   (throw (ex-info "Changed ids missing"
                                   {:kind kind
                                    :ids (mapv :id missing)})))
                 (let [by-id (into {} (map (juxt :id identity)) xs)]
                   (update r kind (partial mapv #(get by-id (:id %) %)))))]
    (as-> repository r (reduce create r created) (reduce change r changed))))

;; ---------------------------------------------------------------------------
;; Rules
;; ---------------------------------------------------------------------------

(def rules
  "Rules on a repository."
  [{:id :repository/shape
    :description
    "a repository holds only persons, users, publications and objections, each as a vector"
    :check (fn [repository]
             (concat (for [k (keys repository)
                           :when (not (contains? empty-repository k))]
                       {:rule :repository/shape
                        :subject {:key k}})
                     (for [k (keys empty-repository)
                           :when (not (vector? (get repository k [])))]
                       {:rule :repository/shape
                        :subject {:key k}})))}])

(defn violations
  "Every rule of every concept broken by `repository`."
  [{:keys [persons users publications objections]
    :as repository}]
  (concat (mapcat #((:check %) repository) rules)
          (objection/violations publications objections persons users)))
