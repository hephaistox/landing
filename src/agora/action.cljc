(ns agora.action
  "Action.

## Definition

An **action** is what someone does in agora. It is a pure function of the repository, who
acts (`by`) and its parameters, returning its changes: what it creates and what it changes.

```clojure
(f repository by parameters) ;=> {:created {:persons [...]} :changed {:publications [...]}}
```

`agora.repository/apply-changes` turns them into the new repository.

The ids are created by the backend, outside the action, and given in its parameters: the
client never chooses one, and the action stays pure.

`by` is `:anonymous` when nobody is logged in, else the logged-in user, `{:id …}`. Each
action declares the grammar of its parameters in its `:parameters` metadata.

An action may **refuse**: it then throws, with the `:reason` of the refusal
(`:not-logged-in`, `:unknown-user`, `:name-taken`…), and nothing changes.

An action checks what it knows to refuse with a precise reason. Then `check-rules!` checks
every rule on the repository its changes give: an action never returns changes breaking a
rule.

## Goals

- One shape for everything someone does, so every action is run and tested the same way.
- Refusals as data: the reason says why, never a bare failure.

## Links

- `agora.repository`: what an action takes, and how its changes apply.
- `agora.entity.user`: who acts."
  (:require
   [agora.entity.publication :as publication]
   [agora.repository         :as repository]))

;; ---------------------------------------------------------------------------
;; Domain
;; ---------------------------------------------------------------------------

(defn refuse!
  "Refuses the action, for `reason`, with some `data` on what was refused."
  ([reason] (refuse! reason {}))
  ([reason data] (throw (ex-info (str "Refused: " (name reason)) (assoc data :reason reason)))))

(defn logged-in-user
  "The user `by` designates in `repository`. Refuses with `:not-logged-in` when nobody is
  logged in, and `:unknown-user` when no user has the id of `by`."
  [repository by]
  (when-not (map? by) (refuse! :not-logged-in))
  (or (some #(when (= (:id by) (:id %)) %) (:users repository))
      (refuse! :unknown-user {:id (:id by)})))

(defn- by-id [xs id] (some #(when (= id (:id %)) %) xs))

(defn new-id!
  "`id`, when no publication, document or objection of `repository` has it. Else refuses
  with `:id-taken`."
  [{:keys [publications objections]} id]
  (when (by-id (concat publications (publication/documents publications) objections) id)
    (refuse! :id-taken {:id id}))
  id)

(defn open-publication-of
  "The publication of `repository` designated by `{:id}`, when open. Refuses with
  `:unknown-publication` when there is none, `:publication-closed` when it is published,
  and `:publication-canceled` when it is canceled."
  [repository {:keys [id]}]
  (let [p (by-id (:publications repository) id)]
    (when-not p (refuse! :unknown-publication {:id id}))
    (when (publication/closed? p) (refuse! :publication-closed {:id id}))
    (when (publication/canceled? p) (refuse! :publication-canceled {:id id}))
    p))

(defn check-rules!
  "`changes`, when the repository they give from `repository` breaks no rule. Else refuses
  with `:rules-broken`, and the `:violations`."
  [repository changes]
  (when-let [violations (seq (repository/violations (repository/apply-changes repository changes)))]
    (refuse! :rules-broken {:violations (mapv #(select-keys % [:rule :detail]) violations)}))
  changes)
