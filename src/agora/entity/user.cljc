(ns agora.entity.user
  "User.

## Definition

A **user** is a person with an **account**: they log in to agora and write in it, with
their Google account or with a password.

Every user is a person. Not every person is a user:

|                                 | person | user                    |
|---------------------------------|--------|-------------------------|
| has a display name              | yes    | yes                     |
| may be attributed a writing     | yes    | yes                     |
| logs in, writes in agora        | no     | yes                     |
| unique display name             | no     | yes, among the users    |

A user's display name is unique. A person without an account's is not: two cited authors
may share a name.

## Goals

- Know **who acts** in agora, and check it at login.
- Tell apart those who write in agora from those agora cites.
- Prevent a user from passing for another: a display name stands for one user only. Names
  are compared regardless of case and accents.

## Examples

- \"Hephaistox\" is a user: he logs in with Google. He recorded the work \"Les misérables\".
- \"Victor hugo\" is a person without an account: that work is attributed to him, but he never logs in.
- \"Agora\" is a user: the account holding the contents of the platform. Its contents are
  owned by a user, so it answers the objections made to them.

## Links

- `agora.entity.person`: a user is a person with an account.

The users the use cases name are in `agora/use_cases/users.edn`. Each holds only
what the account adds to its person: `{:id :provider :provider-id}`. The `:provider-id`
is the id of the account at its provider: at Google, the OAuth link; for a password, the
login, agora being the provider. It is never agora's `:id`. Its name is its person's. No
email, no password."
  (:require
   [auto-core.schema :as schema]
   [clojure.string   :as str]))

;; ---------------------------------------------------------------------------
;; Domain
;; ---------------------------------------------------------------------------

(def providers "How a user logs in - log in method." #{:google :password})

(def provider-schema "How a user logs in, as a grammar." (into [:enum] (sort providers)))

(def provider-id-schema
  "The id of the account at its provider: Google's id, or the login of a password account."
  [:string {:min 1}])

(def schema
  "The grammar of a user: the account of the person of that `:id`, at its provider."
  [:map {:closed true}
   [:id :uuid]
   [:provider provider-schema]
   [:provider-id provider-id-schema]])

(defn validate
  "nil when `u` is a user, else a structured error `{:error :schema :data}`."
  [u]
  (schema/validate-data-humanize schema u))

(defn user? [u] (nil? (validate u)))

(defn- strip-accents
  [s]
  #?(:clj (str/replace (java.text.Normalizer/normalize s java.text.Normalizer$Form/NFD) #"\p{M}" "")
     :cljs (str/replace (.normalize s "NFD") #"[\u0300-\u036f]" "")))

(defn name-key
  "`display-name` as compared for uniqueness: trimmed, lower case, without accents."
  [display-name]
  (-> display-name
      str/trim
      str/lower-case
      strip-accents))

;; ---------------------------------------------------------------------------
;; Rules
;; ---------------------------------------------------------------------------

(def rules
  "Rules on the `persons` and the `users`."
  [{:id :user/shape
    :description "a user is {:id :provider :provider-id}, :provider :google or :password"
    :check (fn [_persons users]
             (for [u users
                   :let [error (validate u)]
                   :when error]
               {:rule :user/shape
                :subject u
                :detail (:error error)}))}
   {:id :user/is-a-person
    :description "a user is a person: its :id is a person's"
    :check (fn [persons users]
             (let [ids (set (map :id persons))]
               (for [u users
                     :when (not (ids (:id u)))]
                 {:rule :user/is-a-person
                  :subject u})))}
   {:id :user/unique-provider-id
    :description "an account at a provider opens one user only"
    :check (fn [_persons users]
             (for [[k us] (group-by (juxt :provider :provider-id) users)
                   :when (> (count us) 1)]
               {:rule :user/unique-provider-id
                :subject (first us)
                :detail k}))}
   {:id :user/unique-name
    :description "a user's name is unique among users, regardless of case and accents"
    :check (fn [persons users]
             (let [user-ids (set (map :id users))]
               (for [[k ps] (group-by (comp name-key :name) (filter #(user-ids (:id %)) persons))
                     :when (> (count ps) 1)]
                 {:rule :user/unique-name
                  :subject (first ps)
                  :detail k})))}])
