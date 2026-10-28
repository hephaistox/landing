(ns agora.action.invite-contributor
  "Inviting a contributor: the owner of an open publication lets another user write in it.

The invitation holds for this publication only, not for its other majors. Another action
revokes it.

An action takes the repository, who acts (`by`) and its parameters, and returns its
changes. Its `:parameters` metadata is the grammar of its parameters."
  (:require
   [agora.action          :as action]
   [agora.value.reference :as reference]))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(def parameters
  "The parameters of `invite-contributor`: the publication, and the user invited."
  [:map {:closed true}
   [:publication reference/publication-schema]
   [:contributor reference/user-schema]])

(defn invite-contributor
  "The owner of the open `publication` invites the user `contributor` to write in it.
  Refuses with:

  - `:not-logged-in`, `:unknown-user`: who acts is no user;
  - `:unknown-publication`: no publication has this `:id`;
  - `:publication-closed`: the publication is published;
  - `:not-the-owner`: who acts does not own the publication;
  - `:not-a-user`: no user has the id of `contributor`;
  - `:already-a-writer`: `contributor` already writes in it, as owner or contributor;
  - `:rules-broken`: the invitation breaks a rule."
  {:parameters parameters}
  [repository by {:keys [publication contributor]}]
  (let [user (action/logged-in-user repository by)
        p (action/open-publication-of repository publication)]
    (when-not (= (:owner p) {:id (:id user)}) (action/refuse! :not-the-owner publication))
    (when-not (some #(= (:id contributor) (:id %)) (:users repository))
      (action/refuse! :not-a-user contributor))
    (when (some #{contributor} (cons (:owner p) (:contributors p)))
      (action/refuse! :already-a-writer contributor))
    (action/check-rules! repository
                         {:changed {:publications [(update p :contributors conj contributor)]}})))
