(ns agora.action.edit-draft
  "Editing a draft: its owner changes what it says, in an open publication.

Only the owner of a draft edits it, not the owner of the publication. The identity of the
draft (type, name, language, major) and its owner never change: another identity is
another draft. What it says (`:content`) is replaced as a whole: title, text, citations,
and the attributes of a work.

An action takes the repository, who acts (`by`) and its parameters, and returns its
changes. Its `:parameters` metadata is the grammar of its parameters."
  (:require
   [agora.action          :as action]
   [agora.entity.document :as document]
   [agora.value.reference :as reference]))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(def kept
  "What editing never changes: the identity of the draft, its language, its owner, and its
  timestamp (a draft has none)."
  #{:type :name :lang :major :minor :owner :timestamp})

(def draft-schema
  "The grammar of the draft edited: its `:id`, and what it now says."
  (into [:map {:closed true}]
        (remove (comp kept first))
        (drop 2 document/schema)))

(def parameters
  "The parameters of `edit-draft`: the publication, and the draft as it now reads."
  [:map {:closed true}
   [:publication reference/publication-schema]
   [:draft draft-schema]])

(defn edit-draft
  "The owner of a draft of the open `publication` replaces what it says by `draft`.
  Refuses with:

  - `:not-logged-in`, `:unknown-user`: who acts is no user;
  - `:unknown-publication`: no publication has this `:id`;
  - `:publication-closed`: the publication is published;
  - `:unknown-draft`: the publication holds no draft with this `:id`;
  - `:not-the-owner`: who acts does not own the draft;
  - `:rules-broken`: the draft breaks a rule, e.g. a link that is no citation."
  {:parameters parameters}
  [repository by {:keys [publication draft]}]
  (let [user (action/logged-in-user repository by)
        p (action/open-publication-of repository publication)
        old (some #(when (= (:id draft) (:id %)) %) (:documents p))]
    (when-not old (action/refuse! :unknown-draft (select-keys draft [:id])))
    (when-not (= (:owner old) {:id (:id user)})
      (action/refuse! :not-the-owner (select-keys draft [:id])))
    (let [d (merge (select-keys old kept) draft)]
      (action/check-rules!
       repository
       {:changed {:publications
                  [(update p :documents (partial mapv #(if (= (:id d) (:id %)) d %)))]}}))))
