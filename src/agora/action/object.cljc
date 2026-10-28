(ns agora.action.object
  "Objecting: a user contests a published version, asking its owner to change it.

Its owner may object too: the objection may come from someone without an account, on a
social network, in real life. A user may have several objections pending on a version,
for different reasons, but one per reason.

The objection takes the language of the version, its major 1, the status `:draft` (it
awaits an answer), and a name drawn from its title; when that name is taken in that
language, a number follows it: `que-veut-dire-rapide-2`.

An action takes the repository, who acts (`by`) and its parameters, and returns its
changes. Its `:parameters` metadata is the grammar of its parameters."
  (:require
   [agora.action             :as action]
   [agora.entity.objection   :as objection]
   [agora.entity.publication :as publication]
   [agora.value.name         :as name]
   [agora.value.reference    :as reference]
   [agora.value.type         :as type]))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(def parameters
  "The parameters of `object`: its id, the version contested, and what it says."
  [:map {:closed true}
   [:id :uuid]
   [:version reference/version-schema]
   [:content objection/content-schema]])

(defn- free-name
  "The name drawn from `title`, followed by a number when an objection of `lang` has it."
  [objections lang title]
  (let [drawn (name/from-title title)
        taken (set (keep #(when (= lang (:lang %)) (:name %)) objections))]
    (first (remove taken (cons drawn (map #(str drawn "-" %) (iterate inc 2)))))))

(defn object
  "Who acts contests the published `version`. Refuses with:

  - `:not-logged-in`, `:unknown-user`: who acts is no user;
  - `:id-taken`: something already has the objection's `:id`;
  - `:unknown-version`: no published version has this `:id`, a draft included;
  - `:unknown-reason`: the type of the version predefines no objection with this `:reason`;
  - `:invalid-backing`: a citation backing it is of a type the reason does not admit;
  - `:already-objected`: who acts has an objection pending on this version, for this reason;
  - `:rules-broken`: the objection breaks a rule."
  {:parameters parameters}
  [repository by {:keys [id version content]}]
  (let [user (action/logged-in-user repository by)
        v (publication/published-version (:publications repository) version)
        _ (action/new-id! repository id)
        _ (when-not v (action/refuse! :unknown-version version))
        reason (type/objection-of (:type v) (:reason content))
        objections (:objections repository)]
    (when-not reason (action/refuse! :unknown-reason (select-keys content [:reason])))
    (when-let [invalid (seq (objection/invalid-backing reason (:inputs content)))]
      (action/refuse! :invalid-backing {:inputs (vec invalid)}))
    (when (some #(and (objection/draft? %)
                      (= {:id (:id user)} (:owner %))
                      (= [{:id (:id v)}] (:documents %))
                      (= (:reason content)
                         (-> %
                             :content
                             :reason)))
                objections)
      (action/refuse! :already-objected (select-keys content [:reason])))
    (action/check-rules! repository
                         {:created {:objections [{:id id
                                                  :type :objection
                                                  :name
                                                  (free-name objections (:lang v) (:title content))
                                                  :lang (:lang v)
                                                  :major 1
                                                  :status :draft
                                                  :owner {:id (:id user)}
                                                  :documents [{:id (:id v)}]
                                                  :content content}]}})))
