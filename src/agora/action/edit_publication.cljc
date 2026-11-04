(ns agora.action.edit-publication
  "Editing a publication: its owner changes what an open publication says.

An open publication is edited in place: its title, the text describing its change, its
date. Its identity, status, contributors and documents never change this way.

An action takes the repository, who acts (`by`) and its parameters, and returns its
changes. Its `:parameters` metadata is the grammar of its parameters."
  (:require
   [agora.action             :as action]
   [agora.entity.publication :as publication]
   [agora.value.reference    :as reference]))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(def parameters
  "The parameters of `edit-publication`: the publication, and what it now says."
  [:map {:closed true}
   [:publication reference/publication-schema]
   [:content publication/content-schema]])

(defn edit-publication
  "The owner of the open `publication` replaces what it says by `content`. Refuses with:

  - `:not-logged-in`, `:unknown-user`: who acts is no user;
  - `:unknown-publication`: no publication has this `:id`;
  - `:publication-closed`, `:publication-canceled`: the publication is no longer open;
  - `:not-the-owner`: who acts does not own the publication, a contributor included;
  - `:rules-broken`: the publication breaks a rule."
  {:parameters parameters}
  [repository by {:keys [publication content]}]
  (let [user (action/logged-in-user repository by)
        p (action/open-publication-of repository publication)]
    (when-not (= (:owner p) {:id (:id user)}) (action/refuse! :not-the-owner publication))
    (action/check-rules! repository {:changed {:publications [(assoc p :content content)]}})))
