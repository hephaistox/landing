(ns agora.action.publish
  "Publishing: the owner of an open publication closes it, and its drafts become published
  versions.

Each draft gets its minor: the next one after the latest published of its major, or 0.
Every citation and link by id becomes a citation by tnlr. The objections the publication
answers are closed. A publication without document is not published: it is canceled, by
another action.

Before closing, the whole integrity is checked: every draft is owned by the owner or a
declared contributor, and the repository it gives breaks no rule.

An action takes the repository, who acts (`by`) and its parameters, and returns its
changes. Its `:parameters` metadata is the grammar of its parameters."
  (:require
   [agora.action           :as action]
   [agora.entity.document  :as document]
   [agora.entity.objection :as objection]
   [agora.value.reference  :as reference]))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(def parameters
  "The parameters of `publish`: the publication, and when it is published."
  [:map {:closed true}
   [:publication reference/publication-schema]
   [:published-at document/published-at-schema]])

(defn publish
  "The owner of the open `publication` publishes it at `published-at`. Refuses with:

  - `:not-logged-in`, `:unknown-user`: who acts is no user;
  - `:unknown-publication`: no publication has this `:id`;
  - `:publication-closed`: the publication is already published;
  - `:not-the-owner`: who acts does not own the publication;
  - `:empty-publication`: the publication holds no document;
  - `:foreign-draft`: a draft is owned by someone neither its owner nor a declared
    contributor;
  - `:rules-broken`: the published repository breaks a rule, e.g. a citation by tnlr of a
    version not yet published."
  {:parameters parameters}
  [repository by {:keys [publication published-at]}]
  (let [user (action/logged-in-user repository by)
        p (action/open-publication-of repository publication)
        writers (set (cons (:owner p) (:contributors p)))]
    (when-not (= (:owner p) {:id (:id user)}) (action/refuse! :not-the-owner publication))
    (when (empty? (:documents p)) (action/refuse! :empty-publication publication))
    (when-let [foreign (seq (remove (comp writers :owner) (:documents p)))]
      (action/refuse! :foreign-draft {:ids (mapv :id foreign)}))
    (let [{closed :publication
           answered :objections}
          (objection/publish p (:publications repository) (:objections repository) published-at)]
      (action/check-rules! repository
                           (cond-> {:changed {:publications [closed]}}
                             (seq answered) (assoc-in [:changed :objections] answered))))))
