(ns agora.action.react
  "Reacting: the author of a closed objection reacts to the change that answered it.

They open the next major of the objection, on a newer version of the document: same name,
same language, pending again. A major is opened only once the previous one is closed: the
answer came first.

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
  "The parameters of `react`: the id of the new major, the objection reacted to, the newer
  version contested, and what the reaction says."
  [:map {:closed true}
   [:id :uuid]
   [:objection reference/objection-schema]
   [:version reference/version-schema]
   [:content objection/content-schema]])

(defn react
  "The author of the closed `objection` opens its next major, on the newer `version`.
  Refuses with:

  - `:not-logged-in`, `:unknown-user`: who acts is no user;
  - `:id-taken`: something already has the new major's `:id`;
  - `:unknown-objection`: no objection has this `:id`;
  - `:not-the-owner`: who acts did not write the objection;
  - `:not-latest`: a later major of the objection exists;
  - `:objection-pending`: the objection still awaits its answer;
  - `:unknown-version`: no published version has this `:id`;
  - `:not-newer`: the version is not a later version of the document contested;
  - `:unknown-reason`: the type of the version predefines no objection with this `:reason`;
  - `:invalid-backing`: a citation backing it is of a type the reason does not admit;
  - `:rules-broken`: the reaction breaks a rule."
  {:parameters parameters}
  [repository
   by
   {:keys [id version content]
    :as parameters}]
  (let [user (action/logged-in-user repository by)
        _ (action/new-id! repository id)
        objections (:objections repository)
        publications (:publications repository)
        o (some #(when (= (get-in parameters [:objection :id]) (:id %)) %) objections)
        _ (when-not o (action/refuse! :unknown-objection (:objection parameters)))
        _ (when-not (= (:owner o) {:id (:id user)})
            (action/refuse! :not-the-owner (:objection parameters)))
        _ (when (some #(and (= ((juxt :name :lang) o) ((juxt :name :lang) %))
                            (> (:major %) (:major o)))
                      objections)
            (action/refuse! :not-latest (:objection parameters)))
        _ (when (objection/draft? o) (action/refuse! :objection-pending (:objection parameters)))
        v (publication/published-version publications version)
        _ (when-not v (action/refuse! :unknown-version version))
        target (publication/published-version publications (first (:documents o)))
        _ (when-not (objection/newer? target v) (action/refuse! :not-newer version))
        reason (type/objection-of (:type v) (:reason content))]
    (when-not reason (action/refuse! :unknown-reason (select-keys content [:reason])))
    (when-let [invalid (seq (objection/invalid-backing reason (:inputs content)))]
      (action/refuse! :invalid-backing {:inputs (vec invalid)}))
    (action/check-rules! repository
                         {:created {:objections [(assoc o
                                                        :id id
                                                        :major (inc (:major o))
                                                        :status :draft
                                                        :documents [{:id (:id v)}]
                                                        :content content)]}})))
