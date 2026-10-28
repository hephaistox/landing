(ns agora.action.sign-up
  "Signing up: someone without an account creates one.

An action takes the repository, who acts (`by`) and its parameters, and returns its
changes. `by` is `:anonymous` when nobody is logged in, else the logged-in user,
`{:id …}`. Each action declares the grammar of its parameters in its `:parameters`
metadata."
  (:require
   [agora.action        :as action]
   [agora.entity.person :as person]
   [agora.entity.user   :as user]))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(def parameters
  "The parameters of `sign-up`: a person, and its account: the provider, and the id of the
  account there, the login for a password."
  (into person/schema [[:provider user/provider-schema] [:provider-id user/provider-id-schema]]))

(defn sign-up
  "Someone without an account signs up: a person, and the user that is its account.
  `by` is `:anonymous`; its parameters are `{:id :name :provider :provider-id}`. Refuses
  with `:already-logged-in` when someone is logged in: one signs up before having an
  account. Refuses with `:already-signed-up` when a user already has this account: the
  same provider and `:provider-id`."
  {:parameters parameters}
  [repository by {:keys [id name provider provider-id]}]
  (when-not (= :anonymous by) (action/refuse! :already-logged-in))
  (when (some #(= [provider provider-id] [(:provider %) (:provider-id %)]) (:users repository))
    (action/refuse! :already-signed-up
                    {:provider provider
                     :provider-id provider-id}))
  {:created {:persons [{:id id
                        :name name}]
             :users [{:id id
                      :provider provider
                      :provider-id provider-id}]}})
