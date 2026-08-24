(ns landing.agora.person.erasure
  "Erasing a person — art. 17.

  The name each document displays, and the identifier it points at. The name is derived, so it leaves the corpus
  the moment the account does. The identifier is the harder one — it was public for as long as the
  account lived, and an id that gathers exactly one person's documents re-links what an anonymous
  byline pretends to have separated. So it goes too: every document is reattributed to the shared
  anonymous person, and the account row is **deleted**, not blanked.

  What is left is a corpus where the erased are one undifferentiated author. The reasoning they
  signed stays where its citations point — no longer personal data, and covered by art. 17(3) for
  the assertions themselves.

  Two ways out, and they differ in what they are allowed to destroy: `erase!` is the account
  holder's own, and takes their private staging with them; `retire!` is an administrator's, and keeps
  everything.

  In `erase!` the steps live together, and in this order: the person's private staging goes first,
  their attribution second, the account row last — once nothing points at it any more, which is the only
  state in which the foreign key allows it to go."
  (:require
   [landing.agora.auth                 :as auth]
   [landing.agora.db.document          :as db-doc]
   [landing.agora.document.publication :as publication]
   [landing.agora.person.attribution   :as attribution]))

(defn- open-publications
  "The cids of `person-id`'s open publications. An open publication is private staging — nothing it
  gathers has been published — so it goes with the account; a closed one published its documents and
  stays, reattributed like everything else."
  [person-id]
  (into []
        (comp (filter #(and (= person-id (:owner-id %)) (= :open (:status %)))) (map :name))
        (db-doc/latest-page {:type :publication
                             :drafts? true})))

(defn retire!
  "Remove person `person-id` on an administrator's initiative: reattribute everything they are
  attributed with to the anonymous person, then delete the account row. Returns
  `{:documents-anonymised n :account-deleted bool}`, or nil for a seeded platform person.

  **Destroys nothing.** That is what separates it from `erase!`, which the account holder asks for
  and which deletes their open publications and the drafts these gather — their own private staging,
  theirs to throw away. An administrator removing a stray person has no such mandate: what that
  person wrote stays, de-attributed, and the drafts stay too, under a byline that names nobody.

  So the two are not the same operation with a different caller. One is a person disposing of their
  own work; the other is the corpus keeping work whose author is being removed from it."
  [person-id]
  (when-not (auth/reserved? person-id)
    (let [{:keys [owned credited]} (attribution/absorb-into-anonymous! person-id)]
      {:documents-anonymised (+ owned credited)
       :account-deleted (auth/delete-account! person-id)})))

(defn erase!
  "Erase person `person-id`: delete their open publications and the drafts these gather, reattribute
  everything else to the anonymous person, then delete the account row. Returns
  `{:publications-deleted n :documents-reattributed n :account-deleted bool}`.

  Refuses the seeded platform people (`auth/reserved?`): absorbing the anonymous person into
  themselves would delete the row every erased document points at, and erasing the platform person
  would hand the seeded corpus to the anonymous bucket and destroy its owner.

  Idempotent in effect — a second call finds no staging, nothing to reattribute and no account."
  [person-id]
  (when-not (auth/reserved? person-id)
    (let [cids (open-publications person-id)
          _ (run! #(publication/delete! person-id %) cids)
          {:keys [owned credited]} (attribution/absorb-into-anonymous! person-id)]
      {:publications-deleted (count cids)
       :documents-reattributed (+ owned credited)
       :account-deleted (auth/delete-account! person-id)})))
