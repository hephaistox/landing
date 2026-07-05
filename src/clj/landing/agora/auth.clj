(ns landing.agora.auth
  "User accounts and email/password authentication (#38).

  Passwords are hashed with bcrypt (buddy-hashers) and never stored or returned
  in clear. Transport security is HTTPS (Clever Cloud in prod). The user model
  also carries `provider`/`provider_id` so OAuth accounts slot in later."
  (:require
   [buddy.hashers        :as hashers]
   [clojure.string       :as str]
   [landing.agora.db     :as db]
   [next.jdbc            :as jdbc]
   [next.jdbc.result-set :as rs])
  (:import (java.util UUID)))

(def admin-emails
  "Accounts permitted to use the maintenance API — the platform owner only. The
  backend is the security boundary; the profile's `:admin` flag is derived from
  this so the frontend can hide admin UI without duplicating the allowlist."
  #{"hephaistox.sc@gmail.com"})

(defn admin?
  "True when `email` belongs to a platform administrator."
  [email]
  (contains? admin-emails email))

(defn- public
  "A user row reduced to a public profile — no password hash. `:admin` is derived
  server-side from the account email (see `admin-emails`)."
  [row]
  (some-> row
          (select-keys [:id :email :display-name :provider :avatar-url :lang])
          (as-> profile (assoc profile :admin (admin? (:email profile))))))

(defn get-user
  "Public profile of the user `id`, or nil. Includes the preferred interface
  language `:lang` (nil when never chosen)."
  [id]
  (public
   (jdbc/execute-one!
    db/ds
    ["SELECT id, email, display_name, provider, avatar_url, lang FROM AGORA_USER WHERE id = ?" id]
    {:builder-fn rs/as-unqualified-kebab-maps})))

(defn set-lang!
  "Persist the user's preferred interface language, and return the updated public
  profile."
  [id lang]
  (jdbc/execute! db/ds ["UPDATE AGORA_USER SET lang = ? WHERE id = ?" lang id])
  (get-user id))

(defn- find-by-email
  [email]
  (jdbc/execute-one!
   db/ds
   ["SELECT id, email, display_name, provider, password_hash FROM AGORA_USER WHERE email = ?" email]
   {:builder-fn rs/as-unqualified-kebab-maps}))

(defn register
  "Create a password account. Returns [:ok profile], or [:error :missing] /
  [:error :email-taken]."
  [{:keys [email password display-name]}]
  (cond
    (or (str/blank? email) (str/blank? password)) [:error :missing]
    (find-by-email email) [:error :email-taken]
    :else
    (let [id (str (UUID/randomUUID))
          hash (hashers/derive password)]
      (jdbc/execute!
       db/ds
       ["INSERT INTO AGORA_USER (id, provider, email, display_name, password_hash, created_at)
         VALUES (?, 'password', ?, ?, ?, UTC_TIMESTAMP())"
        id
        email
        (if (str/blank? display-name) email display-name)
        hash])
      [:ok (get-user id)])))

(defn authenticate
  "Verify `email` + `password` for a password account. Returns the public profile
  or nil."
  [email password]
  (when-let [row (find-by-email email)]
    (when (and (:password-hash row) (:valid (hashers/verify password (:password-hash row))))
      (public row))))

(defn- find-by-provider
  [provider provider-id]
  (jdbc/execute-one!
   db/ds
   ["SELECT id, email, display_name, provider FROM AGORA_USER
     WHERE provider = ? AND provider_id = ?"
    provider
    provider-id]
   {:builder-fn rs/as-unqualified-kebab-maps}))

(defn upsert-oauth-user
  "Find or create the account for an OAuth login (#38). Matches first on
  (provider, provider-id), then on email (loosely linking an existing account),
  else creates a new one. Returns the public profile."
  [{:keys [provider provider-id email display-name avatar-url]}]
  (if-let [existing (or (find-by-provider provider provider-id) (find-by-email email))]
    (do
      ;; keep the avatar fresh on each login (Google's picture URL can change)
      (when avatar-url
        (jdbc/execute!
         db/ds
         ["UPDATE AGORA_USER SET avatar_url = ? WHERE id = ?" avatar-url (:id existing)]))
      (get-user (:id existing)))
    (let [id (str (UUID/randomUUID))]
      (jdbc/execute!
       db/ds
       ["INSERT INTO AGORA_USER (id, provider, provider_id, email, display_name, avatar_url, created_at)
           VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP())"
        id
        provider
        provider-id
        email
        (if (str/blank? display-name) email display-name)
        avatar-url])
      (get-user id))))
