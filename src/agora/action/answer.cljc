(ns agora.action.answer
  "Answering an objection: the owner of the version contested declares that one of their
  open publications answers it.

The publication already holds the change: a later version of the document contested. When
it is published, the objection is closed: closed means a change was made.

An action takes the repository, who acts (`by`) and its parameters, and returns its
changes. Its `:parameters` metadata is the grammar of its parameters."
  (:require
   [agora.action             :as action]
   [agora.entity.objection   :as objection]
   [agora.entity.publication :as publication]
   [agora.value.reference    :as reference]))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(def parameters
  "The parameters of `answer`: the open publication, and the objection it answers."
  [:map {:closed true}
   [:publication reference/publication-schema]
   [:objection reference/objection-schema]])

(defn answer
  "The owner of the open `publication` declares it answers `objection`. Refuses with:

  - `:not-logged-in`, `:unknown-user`: who acts is no user;
  - `:unknown-publication`: no publication has this `:id`;
  - `:publication-closed`, `:publication-canceled`: the publication is no longer open;
  - `:not-the-owner`: who acts does not own the publication;
  - `:unknown-objection`: no objection has this `:id`;
  - `:objection-closed`: the objection is already closed;
  - `:not-the-target-owner`: who acts does not own the version contested;
  - `:already-answered`: the publication already answers the objection;
  - `:no-change`: the publication holds no later version of the document contested;
  - `:rules-broken`: the answer breaks a rule."
  {:parameters parameters}
  [repository
   by
   {:keys [publication]
    :as parameters}]
  (let [user (action/logged-in-user repository by)
        me {:id (:id user)}
        p (action/open-publication-of repository publication)
        _ (when-not (= (:owner p) me) (action/refuse! :not-the-owner publication))
        ref (:objection parameters)
        o (some #(when (= (:id ref) (:id %)) %) (:objections repository))
        _ (when-not o (action/refuse! :unknown-objection ref))
        _ (when (objection/closed? o) (action/refuse! :objection-closed ref))
        target (publication/published-version (:publications repository) (first (:documents o)))]
    (when-not (= (:owner target) me) (action/refuse! :not-the-target-owner ref))
    (when (some #{ref} (:answers p)) (action/refuse! :already-answered ref))
    (when-not (some #(objection/newer? target %) (:documents p)) (action/refuse! :no-change ref))
    (action/check-rules! repository
                         {:changed {:publications [(update p :answers (fnil conj []) ref)]}})))
