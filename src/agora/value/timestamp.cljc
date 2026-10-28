(ns agora.value.timestamp
  "Timestamp.

## Definition

A **timestamp** proves that a published version existed at a date, without trusting agora.
It is a key of the version (`:timestamp`). The owner of the version asks for it, when its
type allows it (a prediction: one proves it was made before what it predicts), and records
its proof. A minor version has one timestamp at most: it is a key.

It holds the **hash** of the version, taken when it is asked for. The hash covers what never
changes once published: the identity, the id of the owner and the content. The name of the
owner, read from the persons, is outside: renaming a person never breaks a timestamp. The
timestamp itself is outside too.

The hash is anchored in Bitcoin by OpenTimestamps, outside agora, which returns a **proof**.
The status:

- `:pending`: asked for, no proof yet;
- `:incomplete`: a proof from the OpenTimestamps calendars, not yet anchored in a block;
- `:complete`: a proof anchored in a Bitcoin block.

A status only moves forward.

## Goals

- Prove, against anyone, that a prediction was made before its date.
- Never change what the version says: the timestamp is beside its content.

## Examples

```clojure
{:id #uuid \"…\" :type :prediction :name \"ventes-2030\" :lang :fr :major 1 :minor 0
 :owner {:id #uuid \"…\"}
 :content {:title \"Les ventes de 2030\" :text \"…\" :inputs [] :published-at #inst \"…\"}
 :timestamp {:hash \"04ee5cc5…\" :requested-at #inst \"2026-10-27T09:00:00Z\" :status :pending}}
```

## Links

- `agora.value.hash`: the hash of the version.
- `agora.value.type`: the types a timestamp applies to.
- `agora.value.version`: only a published version is timestamped."
  (:require
   [agora.value.hash    :as hash]
   [agora.value.type    :as type]
   [agora.value.version :as version]))

;; ---------------------------------------------------------------------------
;; Domain
;; ---------------------------------------------------------------------------

(def statuses
  "The statuses of a timestamp, in the order they move forward."
  [:pending :incomplete :complete])

(def schema
  "The grammar of a timestamp: the hash of the version, when it was asked for, the status,
  and the proof once there is one."
  [:and
   [:map {:closed true}
    [:hash hash/schema]
    [:requested-at inst?]
    [:status (into [:enum] statuses)]
    [:proof {:optional true}
     [:string {:min 1}]]]
   [:fn {:error/message "a proof exactly when not pending"}
    #(= (= :pending (:status %)) (nil? (:proof %)))]])

(defn advances?
  "Does the status `to` come after `from`?"
  [from to]
  (< (.indexOf statuses from) (.indexOf statuses to)))

(defn hash-of
  "The hash of `version`: its identity, the id of its owner and its content."
  [version]
  (hash/of (select-keys version [:id :type :name :lang :major :minor :owner :content])))

;; ---------------------------------------------------------------------------
;; Rules
;; ---------------------------------------------------------------------------

(def rules
  "Rules on a sequence of documents."
  [{:id :timestamp/version
    :description "only a published version, of a type that allows it, is timestamped"
    :check (fn [documents]
             (for [d documents
                   :when (and (:timestamp d)
                              (not (and (version/published? d) (type/timestamp? (:type d)))))]
               {:rule :timestamp/version
                :subject d}))}
   {:id :timestamp/hash
    :description "the hash of a timestamp is the hash of its version"
    :check (fn [documents]
             (for [d documents
                   :when (and (:timestamp d) (not= (get-in d [:timestamp :hash]) (hash-of d)))]
               {:rule :timestamp/hash
                :subject d}))}])
