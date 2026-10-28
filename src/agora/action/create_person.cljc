(ns agora.action.create-person
  "Creating a person: a logged-in user records someone without an account, to credit a
  work to them.

An action takes the repository, who acts (`by`) and its parameters, and returns its
changes. Its `:parameters` metadata is the grammar of its parameters."
  (:require
   [agora.action        :as action]
   [agora.entity.person :as person]))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(def parameters "The parameters of `create-person`: the person itself." person/schema)

(defn create-person
  "A logged-in user creates a person without an account. `by` is that user, `{:id …}`; the
  parameters are the person, `{:id :name}`. Two persons may share a name. Refuses with
  `:not-logged-in` when nobody is logged in, and `:unknown-user` when no user has the id of
  `by`."
  {:parameters parameters}
  [repository by person]
  (action/logged-in-user repository by)
  {:created {:persons [person]}})
