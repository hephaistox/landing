(ns agora.action.record-timestamp
  "Recording a timestamp: the owner of a version writes the proof OpenTimestamps returned.

The calendars first return an incomplete proof; once anchored in a Bitcoin block, the
complete one replaces it. The status only moves forward. Checking the proof against the
hash is the job of OpenTimestamps, outside agora.

An action takes the repository, who acts (`by`) and its parameters, and returns its
changes. Its `:parameters` metadata is the grammar of its parameters."
  (:require
   [agora.action             :as action]
   [agora.entity.publication :as publication]
   [agora.value.reference    :as reference]
   [agora.value.timestamp    :as timestamp]))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(def parameters
  "The parameters of `record-timestamp`: the version, its proof (OpenTimestamps, in base64),
  and the status that proof gives."
  [:map {:closed true}
   [:version reference/version-schema]
   [:proof [:string {:min 1}]]
   [:status [:enum :incomplete :complete]]])

(defn record-timestamp
  "The owner of `version` writes `proof` on its timestamp, which takes `status`. Refuses
  with:

  - `:not-logged-in`, `:unknown-user`: who acts is no user;
  - `:unknown-version`: no published version has this `:id`;
  - `:not-the-owner`: who acts does not own the version;
  - `:not-timestamped`: the version has no timestamp to record;
  - `:status-not-advancing`: the status does not come after the current one;
  - `:rules-broken`: the timestamp breaks a rule."
  {:parameters parameters}
  [repository by {:keys [version proof status]}]
  (let [user (action/logged-in-user repository by)
        publications (:publications repository)
        v (publication/published-version publications version)
        t (:timestamp v)]
    (when-not v (action/refuse! :unknown-version version))
    (when-not (= (:owner v) {:id (:id user)}) (action/refuse! :not-the-owner version))
    (when-not t (action/refuse! :not-timestamped version))
    (when-not (timestamp/advances? (:status t) status)
      (action/refuse! :status-not-advancing
                      {:from (:status t)
                       :to status}))
    (action/check-rules!
     repository
     {:changed {:publications [(publication/update-version
                                publications
                                version
                                #(update % :timestamp assoc :proof proof :status status))]}})))
