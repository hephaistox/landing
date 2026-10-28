(ns agora.action.cancel-publication
  "Canceling a publication: its owner gives up an open publication.

It keeps its drafts, frozen, never published: nothing is lost. Its name stays taken: the
status is not part of the identity. No text says why.

Before canceling, the whole integrity is checked, as for publishing: every draft is owned
by the owner or a declared contributor.

An action takes the repository, who acts (`by`) and its parameters, and returns its
changes. Its `:parameters` metadata is the grammar of its parameters."
  (:require
   [agora.action          :as action]
   [agora.value.reference :as reference]))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(def parameters
  "The parameters of `cancel-publication`: the publication."
  [:map {:closed true}
   [:publication reference/publication-schema]])

(defn cancel-publication
  "The owner of the open `publication` cancels it: its drafts stay, frozen. Refuses with:

  - `:not-logged-in`, `:unknown-user`: who acts is no user;
  - `:unknown-publication`: no publication has this `:id`;
  - `:publication-closed`: the publication is published;
  - `:publication-canceled`: the publication is already canceled;
  - `:not-the-owner`: who acts does not own the publication;
  - `:foreign-draft`: a draft is owned by someone neither its owner nor a declared
    contributor;
  - `:rules-broken`: the canceled publication breaks a rule."
  {:parameters parameters}
  [repository by {:keys [publication]}]
  (let [user (action/logged-in-user repository by)
        p (action/open-publication-of repository publication)
        writers (set (cons (:owner p) (:contributors p)))]
    (when-not (= (:owner p) {:id (:id user)}) (action/refuse! :not-the-owner publication))
    (when-let [foreign (seq (remove (comp writers :owner) (:documents p)))]
      (action/refuse! :foreign-draft {:ids (mapv :id foreign)}))
    (action/check-rules! repository {:changed {:publications [(assoc p :status :canceled)]}})))
