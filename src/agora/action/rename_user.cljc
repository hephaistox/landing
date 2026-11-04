(ns agora.action.rename-user
  "Renaming a user: a user changes the name agora shows for them.

The name belongs to the person: everything they wrote designates them by their id, so it
shows the new name at once, and no timestamp breaks. A user's name stays unique among the
users, regardless of case and accents: nobody passes for another.

An action takes the repository, who acts (`by`) and its parameters, and returns its
changes. Its `:parameters` metadata is the grammar of its parameters."
  (:require
   [agora.action      :as action]
   [agora.entity.user :as user]))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(def parameters
  "The parameters of `rename-user`: the new name."
  [:map {:closed true}
   [:name [:string {:min 1}]]])

(defn rename-user
  "Who acts takes `name`. Refuses with:

  - `:not-logged-in`, `:unknown-user`: who acts is no user;
  - `:name-taken`: another user has that name, regardless of case and accents;
  - `:rules-broken`: the name breaks a rule."
  {:parameters parameters}
  [{:keys [persons users]
    :as repository}
   by
   {:keys [name]}]
  (let [me (action/logged-in-user repository by)
        user-ids (set (map :id users))]
    (when (some #(and (user-ids (:id %))
                      (not= (:id me) (:id %))
                      (= (user/name-key name) (user/name-key (:name %))))
                persons)
      (action/refuse! :name-taken {:name name}))
    (action/check-rules! repository
                         {:changed {:persons [{:id (:id me)
                                               :name name}]}})))
