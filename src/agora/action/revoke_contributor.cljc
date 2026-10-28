(ns agora.action.revoke-contributor
  "Revoking a contributor: the owner of an open publication stops a user writing in it.

The contributor first deletes their drafts: revoking is refused while they have some, so
nothing they wrote changes owner nor disappears silently.

An action takes the repository, who acts (`by`) and its parameters, and returns its
changes. Its `:parameters` metadata is the grammar of its parameters."
  (:require
   [agora.action          :as action]
   [agora.value.reference :as reference]))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(def parameters
  "The parameters of `revoke-contributor`: the publication, and the contributor."
  [:map {:closed true}
   [:publication reference/publication-schema]
   [:contributor reference/user-schema]])

(defn revoke-contributor
  "The owner of the open `publication` revokes `contributor`. Refuses with:

  - `:not-logged-in`, `:unknown-user`: who acts is no user;
  - `:unknown-publication`: no publication has this `:id`;
  - `:publication-closed`, `:publication-canceled`: the publication is no longer open;
  - `:not-the-owner`: who acts does not own the publication;
  - `:not-invited`: `contributor` is not a contributor of the publication;
  - `:has-drafts`: `contributor` still has drafts in the publication;
  - `:rules-broken`: the revocation breaks a rule."
  {:parameters parameters}
  [repository by {:keys [publication contributor]}]
  (let [user (action/logged-in-user repository by)
        p (action/open-publication-of repository publication)]
    (when-not (= (:owner p) {:id (:id user)}) (action/refuse! :not-the-owner publication))
    (when-not (some #{contributor} (:contributors p)) (action/refuse! :not-invited contributor))
    (when (some #(= contributor (:owner %)) (:documents p))
      (action/refuse! :has-drafts contributor))
    (action/check-rules! repository
                         {:changed
                          {:publications
                           [(update p :contributors (partial filterv #(not= contributor %)))]}})))
