(ns agora.action.prepare-timestamp
  "Preparing a timestamp: the owner of a published version asks to prove it existed now.

The version gets its timestamp, pending, holding its hash: OpenTimestamps, outside agora,
anchors it, and `record-timestamp` writes its proof.

An action takes the repository, who acts (`by`) and its parameters, and returns its
changes. Its `:parameters` metadata is the grammar of its parameters."
  (:require
   [agora.action             :as action]
   [agora.entity.document    :as document]
   [agora.entity.publication :as publication]
   [agora.value.reference    :as reference]
   [agora.value.timestamp    :as timestamp]
   [agora.value.type         :as type]))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(def parameters
  "The parameters of `prepare-timestamp`: the version, and when it is asked for."
  [:map {:closed true}
   [:version reference/version-schema]
   [:requested-at document/published-at-schema]])

(defn prepare-timestamp
  "The owner of the published `version` asks to timestamp it: a pending timestamp holding
  its hash. Refuses with:

  - `:not-logged-in`, `:unknown-user`: who acts is no user;
  - `:unknown-version`: no published version has this `:id`, a draft included;
  - `:not-the-owner`: who acts does not own the version;
  - `:not-timestampable`: the type of the version may not be timestamped;
  - `:already-timestamped`: the version already has its timestamp;
  - `:rules-broken`: the timestamp breaks a rule."
  {:parameters parameters}
  [repository by {:keys [version requested-at]}]
  (let [user (action/logged-in-user repository by)
        publications (:publications repository)
        v (publication/published-version publications version)]
    (when-not v (action/refuse! :unknown-version version))
    (when-not (= (:owner v) {:id (:id user)}) (action/refuse! :not-the-owner version))
    (when-not (type/timestamp? (:type v)) (action/refuse! :not-timestampable version))
    (when (:timestamp v) (action/refuse! :already-timestamped version))
    (action/check-rules! repository
                         {:changed {:publications [(publication/update-version
                                                    publications
                                                    version
                                                    #(assoc %
                                                            :timestamp
                                                            {:hash (timestamp/hash-of v)
                                                             :requested-at requested-at
                                                             :status :pending}))]}})))
