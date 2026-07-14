(ns landing.agora.auth
  "User accounts and email/password authentication.

  Passwords are hashed with bcrypt (buddy-hashers) and never stored or returned
  in clear. Transport security is HTTPS (Clever Cloud in prod). The user model
  also carries `provider`/`provider_id` so OAuth accounts slot in later."
  (:require
   [auto-core.log        :as core-log]
   [buddy.hashers        :as hashers]
   [clojure.string       :as str]
   [landing.agora.db     :as db]
   [next.jdbc            :as jdbc]
   [next.jdbc.result-set :as rs])
  (:import (java.sql SQLException SQLIntegrityConstraintViolationException)
           (java.util UUID)))

(def admin-emails
  "Accounts permitted to use the maintenance API — the platform owner(s). Read from the
  `AGORA_ADMIN_EMAILS` env var (comma- or whitespace-separated) so the allowlist is
  configured per deployment, not baked into the source. Empty when the var is unset →
  no admin accounts. The backend is the security boundary; the profile's `:admin` flag
  is derived from this so the frontend can hide admin UI without duplicating the list."
  (into #{}
        (comp (map str/trim) (remove str/blank?))
        (str/split (or (System/getenv "AGORA_ADMIN_EMAILS") "") #"[,\s]+")))

(def min-password-length
  "Minimum length for a password account. Kept modest (NIST favours length over
  complexity rules); the API also caps the maximum to bound bcrypt work."
  8)

(defn admin?
  "True when the account is a platform administrator: an allow-listed email **that
  authenticated through Google**. Password accounts never qualify — email addresses
  can be registered without proof of ownership, so admin must be tied to a verified
  OAuth identity, not the email string alone."
  [provider email]
  (and (= provider "google") (contains? admin-emails email)))

(defn- public
  "A user row reduced to a public profile — no password hash. `:admin` is derived
  server-side from the account provider + email (see `admin?`)."
  [row]
  (some-> row
          (select-keys [:id :email :display-name :provider :avatar-url :lang])
          (as-> profile (assoc profile :admin (admin? (:provider profile) (:email profile))))))

(defn get-user
  "Public profile of the user `id`, or nil. Includes the preferred interface
  language `:lang` (nil when never chosen)."
  [id]
  (public
   (jdbc/execute-one!
    db/ds
    ["SELECT id, email, display_name, provider, avatar_url, lang FROM AGORA_USER WHERE id = ?" id]
    {:builder-fn rs/as-unqualified-kebab-maps})))

(defn author-profile
  "The *public author card* for user `id`, or nil: display name, avatar and the
  account-creation date. Deliberately excludes email and any private field — this is
  shown on the public author page, to anyone."
  [id]
  (jdbc/execute-one! db/ds
                     ["SELECT id, display_name, avatar_url, created_at FROM AGORA_USER WHERE id = ?"
                      id]
                     {:builder-fn rs/as-unqualified-kebab-maps}))

(defn create-external-person!
  "Create a login-less **external** person — a cited author with no account (e.g.
  \"Sun Tzu\"), stored as an AGORA_USER row with `provider='external'` and no
  email/password. Returns {:id :display-name}. Used when granting a source whose author
  isn't a platform member."
  [display-name]
  (let [id (str (UUID/randomUUID))
        name (str/trim (or display-name ""))]
    (jdbc/execute!
     db/ds
     ["INSERT INTO AGORA_USER (id, provider, display_name, created_at)
       VALUES (?, 'external', ?, UTC_TIMESTAMP())"
      id
      name])
    {:id id
     :display-name name}))

(defn find-or-create-external!
  "The external (login-less) person named exactly `display-name`, creating it if absent.
  Used for platform/seed authors (e.g. \"Agora\", cited historical figures) so they are
  normal `AGORA_USER` rows with real profiles — not nil-owner author strings. Returns
  {:id :display-name}."
  [display-name]
  (let [nm (str/trim (or display-name ""))]
    (or
     (jdbc/execute-one!
      db/ds
      ["SELECT id, display_name FROM AGORA_USER
            WHERE provider = 'external' AND display_name = ? LIMIT 1"
       nm]
      {:builder-fn rs/as-unqualified-kebab-maps})
     (create-external-person! nm))))

(defn search-people
  "People whose display name matches `q` — [{:id :display-name}…], for the author
  picker (both real accounts and external cited people). Blank `q` → []."
  [q]
  (if (str/blank? q)
    []
    (jdbc/execute!
     db/ds
     ["SELECT id, display_name FROM AGORA_USER
        WHERE display_name LIKE ? ORDER BY display_name LIMIT 20"
      (str "%" (str/trim q) "%")]
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
  "Create a password account. Returns one of:
   - [:ok profile]            on success
   - [:error :missing]        blank email or password
   - [:error :weak-password]  password shorter than the minimum length
   - [:error :email-taken]    email already registered (incl. a lost insert race
                              on the UNIQUE constraint)
   - [:error :db-error]       the database is unreachable or failing

  The DB calls (find-by-email / insert) raise java.sql.SQLException on failure, so
  they are caught here and surfaced as a distinct error rather than bubbling up as
  an unhandled 500."
  [{:keys [email password display-name]}]
  (cond
    (or (str/blank? email) (str/blank? password)) [:error :missing]
    (< (count password) min-password-length) [:error :weak-password]
    :else
    (try
      (if (find-by-email email)
        [:error :email-taken]
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
          [:ok (get-user id)]))
      (catch SQLIntegrityConstraintViolationException _
        ;; a concurrent registration inserted the same email between our check and
        ;; insert — treat the UNIQUE violation as the email being taken.
        [:error :email-taken])
      (catch SQLException e
        (core-log/error-exception e "register: database error")
        [:error :db-error]))))

(defn authenticate
  "Verify `email` + `password` for a password account. Returns one of:
   - [:ok profile]       valid credentials
   - [:error :invalid]   no such account, not a password account, or wrong password
   - [:error :db-error]  the database is unreachable or failing

  find-by-email raises java.sql.SQLException on DB failure; it is caught here so a
  DB outage surfaces as a distinct error rather than being mistaken for invalid
  credentials."
  [email password]
  (try (if-let [row (find-by-email email)]
         (if (and (:password-hash row) (:valid (hashers/verify password (:password-hash row))))
           [:ok (public row)]
           [:error :invalid])
         [:error :invalid])
       (catch SQLException e
         (core-log/error-exception e "authenticate: database error")
         [:error :db-error])))

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
  "Find or create the account for an OAuth login. Matches first on
  (provider, provider-id), then on email (loosely linking an existing account),
  else creates a new one. Returns the public profile, or nil when the database is
  unreachable/failing — the caller then treats the login as failed.

  The DB calls raise java.sql.SQLException on failure; caught here (a UNIQUE race
  from a concurrent first login lands here too, so the user simply retries)."
  [{:keys [provider provider-id email display-name avatar-url]}]
  (try
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
        (get-user id)))
    (catch SQLException e (core-log/error-exception e "upsert-oauth-user: database error") nil)))
