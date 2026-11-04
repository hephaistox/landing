(ns agora.action.edit-objection
  "Editing an objection: its author changes what a pending objection says.

What it says is replaced as a whole: title, text, reason, backing citations. Its name,
language, major and the version it contests never change. Once a publication declares to
answer it, it is frozen: the answer was prepared from what it said. To say something else,
its author writes another objection.

An action takes the repository, who acts (`by`) and its parameters, and returns its
changes. Its `:parameters` metadata is the grammar of its parameters."
  (:require
   [agora.action             :as action]
   [agora.entity.objection   :as objection]
   [agora.entity.publication :as publication]
   [agora.value.reference    :as reference]
   [agora.value.type         :as type]))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(def parameters
  "The parameters of `edit-objection`: the objection, and what it now says."
  [:map {:closed true}
   [:objection reference/objection-schema]
   [:content objection/content-schema]])

(defn edit-objection
  "The author of the pending `objection` replaces what it says by `content`. Refuses with:

  - `:not-logged-in`, `:unknown-user`: who acts is no user;
  - `:unknown-objection`: no objection has this `:id`;
  - `:not-the-owner`: who acts did not write the objection;
  - `:objection-closed`, `:objection-canceled`: the objection is no longer pending;
  - `:objection-answered`: a publication declares to answer it;
  - `:unknown-reason`: the type of the version contested predefines no objection with this
    `:reason`;
  - `:invalid-backing`: a citation backing it is of a type the reason does not admit;
  - `:rules-broken`: the objection breaks a rule."
  {:parameters parameters}
  [repository
   by
   {ref :objection
    content :content}]
  (let [user (action/logged-in-user repository by)
        publications (:publications repository)
        o (some #(when (= (:id ref) (:id %)) %) (:objections repository))]
    (when-not o (action/refuse! :unknown-objection ref))
    (when-not (= (:owner o) {:id (:id user)}) (action/refuse! :not-the-owner ref))
    (when (objection/closed? o) (action/refuse! :objection-closed ref))
    (when (objection/canceled? o) (action/refuse! :objection-canceled ref))
    (when (some #(some #{ref} (:answers %)) publications) (action/refuse! :objection-answered ref))
    (let [v (publication/published-version publications (first (:documents o)))
          reason (type/objection-of (:type v) (:reason content))]
      (when-not reason (action/refuse! :unknown-reason (select-keys content [:reason])))
      (when-let [invalid (seq (objection/invalid-backing reason (:inputs content)))]
        (action/refuse! :invalid-backing {:inputs (vec invalid)}))
      (action/check-rules! repository {:changed {:objections [(assoc o :content content)]}}))))
