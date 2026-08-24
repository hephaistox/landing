(ns landing.agora.person.attribution
  "Moving what a person is attributed with from one person to another.

  A document points at two people — its **owner**, accountable for it, and, on a work, the
  **credited author** it records. Both are columns on the row, not fields of the immutable content,
  precisely so they can move: attribution has a lifecycle the claim does not.

  One operation covers every reason it moves. Today that reason is an erasure, absorbing an account
  into `auth/anonymous-id`; the transfer of a document to another contributor is the same `UPDATE` with a
  different target, and lands here when it is built.

  The byline a document displays is derived from whoever it points at, so reattributing and
  re-deriving are one step — never two a caller could get half-right."
  (:require
   [landing.agora.admin              :as admin]
   [landing.agora.auth               :as auth]
   [landing.agora.db.document        :as db-doc]
   [landing.agora.document.cached-db :as cached-db]))

(defn reattribute!
  "Point everything `from-id` owns, and everything credited to them, at `to-id`; re-derive the
  bylines that name either of them, and clear the read caches. Returns
  `{:owned n :credited n :bylines-refreshed n}`.

  Both people's bylines are re-derived, not just the target's: a document that moved now names
  `to-id`, and one that did not move may still cache a name `from-id` is about to stop having."
  [from-id to-id]
  (let [moved (db-doc/reattribute! from-id to-id)
        refreshed (+ (:refreshed (admin/refresh-author! to-id))
                     (:refreshed (admin/refresh-author! from-id)))]
    (cached-db/clear!)
    (assoc moved :bylines-refreshed refreshed)))

(defn absorb-into-anonymous!
  "Reattribute everything `person-id` is attributed with to the shared anonymous person. What they
  wrote stays where its citations point; the person it pointed at stops being anybody in particular.

  Leaves nothing referencing `person-id`, which is what lets the account row be deleted at all — the
  foreign key refuses while a single document still points there."
  [person-id]
  (reattribute! person-id auth/anonymous-id))

(defn anonymous
  "The shared anonymous person's public card, or nil when the schema has not been seeded."
  []
  (auth/author-profile auth/anonymous-id))
