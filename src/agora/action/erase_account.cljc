(ns agora.action.erase-account
  "Erasing an account: a user has their person and their account deleted.

What they wrote does not disappear: it is reattributed to Anonymus, one shared person, so
what survives groups every erased person together and identifies none. Their publications,
documents, objections, places as contributor and the works credited to them all go to
Anonymus. Only the attribution changes, never what is written: this is the one action
changing what others hold, the drafts they wrote in others' publications, because the law
asks it.

Their timestamps are removed: a proof of anteriority only serves the person claiming it,
and its hash holds their id. Their publications still open are canceled: nobody would
ever publish them.

An action takes the repository, who acts (`by`) and its parameters, and returns its
changes. Its `:parameters` metadata is the grammar of its parameters."
  (:require
   [agora.action             :as action]
   [agora.entity.publication :as publication]
   [agora.entity.user        :as user]))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(def parameters
  "The parameters of `erase-account`: none, who acts is erased."
  [:map {:closed true}])

(defn erase-account
  "Who acts erases their account. Refuses with:

  - `:not-logged-in`, `:unknown-user`: who acts is no user;
  - `:reserved-account`: who acts is Anonymus;
  - `:rules-broken`: the erasure breaks a rule."
  {:parameters parameters}
  [{:keys [persons publications objections]
    :as repository}
   by
   _]
  (let [me {:id (:id (action/logged-in-user repository by))}
        anon {:id (get-in user/anonymus [:person :id])}
        _ (when (= me anon) (action/refuse! :reserved-account me))
        swap #(if (= me %) anon %)
        erase-doc (fn [d]
                    (cond-> (update d :owner swap)
                      (= me (:owner d)) (dissoc :timestamp)
                      (= me (:author d)) (assoc :author anon)))
        mine? (fn [p]
                (or (= me (:owner p))
                    (some #{me} (:contributors p))
                    (some #(or (= me (:owner %)) (= me (:author %))) (:documents p))))
        changed-publications
        (vec (for [p publications
                   :when (mine? p)]
               (cond-> (-> p
                           (update :owner swap)
                           (update :contributors #(vec (distinct (map swap %))))
                           (update :documents (partial mapv erase-doc)))
                 (and (= me (:owner p)) (publication/open? p)) (assoc :status :canceled))))
        changed-objections (vec (for [o objections :when (= me (:owner o))] (assoc o :owner anon)))]
    (action/check-rules!
     repository
     (cond-> {:deleted {:persons [me]
                        :users [me]}}
       (not (some #(= anon {:id (:id %)}) persons)) (assoc :created
                                                           {:persons [(:person user/anonymus)]
                                                            :users [(:user user/anonymus)]})
       (seq changed-publications) (assoc-in [:changed :publications] changed-publications)
       (seq changed-objections) (assoc-in [:changed :objections] changed-objections)))))
