(ns agora.action.cancel-objection
  "Canceling an objection: its author takes back a pending objection, mistaken or
  convinced.

The objection stays visible, canceled: it awaits nothing any more, and is never reacted to.
Canceling changes nothing but the objection: an open publication that declares to answer
it keeps its answer, and publishing it later leaves the objection canceled.

An action takes the repository, who acts (`by`) and its parameters, and returns its
changes. Its `:parameters` metadata is the grammar of its parameters."
  (:require
   [agora.action           :as action]
   [agora.entity.objection :as objection]
   [agora.value.reference  :as reference]))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(def parameters
  "The parameters of `cancel-objection`: the objection."
  [:map {:closed true}
   [:objection reference/objection-schema]])

(defn cancel-objection
  "The author of the pending `objection` cancels it. Refuses with:

  - `:not-logged-in`, `:unknown-user`: who acts is no user;
  - `:unknown-objection`: no objection has this `:id`;
  - `:not-the-owner`: who acts did not write the objection;
  - `:objection-closed`: the objection was answered, so a change was made;
  - `:objection-canceled`: the objection is already canceled;
  - `:rules-broken`: the cancellation breaks a rule."
  {:parameters parameters}
  [repository by {ref :objection}]
  (let [user (action/logged-in-user repository by)
        o (some #(when (= (:id ref) (:id %)) %) (:objections repository))]
    (when-not o (action/refuse! :unknown-objection ref))
    (when-not (= (:owner o) {:id (:id user)}) (action/refuse! :not-the-owner ref))
    (when (objection/closed? o) (action/refuse! :objection-closed ref))
    (when (objection/canceled? o) (action/refuse! :objection-canceled ref))
    (action/check-rules! repository {:changed {:objections [(assoc o :status :canceled)]}})))
