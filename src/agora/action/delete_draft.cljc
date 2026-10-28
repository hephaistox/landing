(ns agora.action.delete-draft
  "Deleting a draft: its owner, or the owner of the publication, removes it from an open
  publication.

The owner of the publication has priority over its contributors: they delete any draft, so
no contributor can block the publication with a draft they never amend nor delete. They
never edit it: only its owner does.

A draft was never published: nobody outside its publication relies on it. Inside, another
draft may cite it by its id; deleting it would break that citation, written by someone
else, so it is refused.

An action takes the repository, who acts (`by`) and its parameters, and returns its
changes. Its `:parameters` metadata is the grammar of its parameters."
  (:require
   [agora.action          :as action]
   [agora.value.citation  :as citation]
   [agora.value.reference :as reference]))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(def parameters
  "The parameters of `delete-draft`: the publication, and the draft."
  [:map {:closed true}
   [:publication reference/publication-schema]
   [:draft reference/version-schema]])

(defn delete-draft
  "The owner of `draft`, or of the open `publication`, deletes the draft. Refuses with:

  - `:not-logged-in`, `:unknown-user`: who acts is no user;
  - `:unknown-publication`: no publication has this `:id`;
  - `:publication-closed`, `:publication-canceled`: the publication is no longer open;
  - `:unknown-draft`: the publication holds no draft with this `:id`;
  - `:not-the-owner`: who acts owns neither the draft nor the publication;
  - `:draft-cited`: another draft of the publication cites it by its id;
  - `:rules-broken`: the publication without it breaks a rule."
  {:parameters parameters}
  [repository by {:keys [publication draft]}]
  (let [user (action/logged-in-user repository by)
        p (action/open-publication-of repository publication)
        d (some #(when (= (:id draft) (:id %)) %) (:documents p))
        citing (filter (fn [x]
                         (some #(and (citation/by-id? %) (= (:id draft) (:id %)))
                               (-> x
                                   :content
                                   :inputs)))
                       (:documents p))]
    (when-not d (action/refuse! :unknown-draft draft))
    (when-not (some #{{:id (:id user)}} [(:owner d) (:owner p)])
      (action/refuse! :not-the-owner draft))
    (when (seq citing) (action/refuse! :draft-cited {:by (mapv #(select-keys % [:id]) citing)}))
    (action/check-rules!
     repository
     {:changed {:publications
                [(update p :documents (partial filterv #(not= (:id draft) (:id %))))]}})))
