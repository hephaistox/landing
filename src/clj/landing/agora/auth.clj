(ns landing.agora.auth
  "User accounts and email/password authentication.

  Passwords are hashed with bcrypt (buddy-hashers) and never stored or returned
  in clear. Transport security is HTTPS (Clever Cloud in prod). The user model
  also carries `provider`/`provider_id` so OAuth accounts slot in later.

  **No account ever carries a civil identity.** The public name is an alias drawn
  here at creation, for every provider — what Google hands us (a real name, a
  photo) is used to authenticate and then dropped. On a platform whose subject
  matter is political, philosophical and religious positions, the default cannot
  be the user's legal name."
  (:require
   [auto-core.log              :as core-log]
   [buddy.hashers              :as hashers]
   [clojure.string             :as str]
   [landing.agora.db           :as db]
   [landing.agora.person.alias :as person-alias]
   [next.jdbc                  :as jdbc]
   [next.jdbc.result-set       :as rs])
  (:import (java.security SecureRandom)
           (java.sql SQLException SQLIntegrityConstraintViolationException)
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

(defn display-name
  "The public name of person `id`, or nil when unknown. The single source of every byline: a
  document caches it in its derived `computed`, so a rename or an erasure of this row propagates."
  [id]
  (when id
    (:display-name (jdbc/execute-one! db/ds
                                      ["SELECT display_name FROM AGORA_USER WHERE id = ?" id]
                                      {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn display-names
  "Every person as `id → display_name`. One query, so the reconcile can derive the whole corpus's
  bylines without a lookup per document."
  []
  (into {}
        (map (juxt :id :display-name))
        (jdbc/execute! db/ds
                       ["SELECT id, display_name FROM AGORA_USER"]
                       {:builder-fn rs/as-unqualified-kebab-maps})))

(defn create-external-person!
  "Create a login-less **external** person — a cited author with no account (e.g.
  \"Sun Tzu\"), stored as an AGORA_USER row with `provider='external'` and no
  email/password. Returns {:id :display-name}. Used when citing a work whose author
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
      (str "%" (db/escape-like (str/trim q)) "%")]
     {:builder-fn rs/as-unqualified-kebab-maps})))

(defn set-lang!
  "Persist the user's preferred interface language, and return the updated public
  profile."
  [id lang]
  (jdbc/execute! db/ds ["UPDATE AGORA_USER SET lang = ? WHERE id = ?" lang id])
  (get-user id))

;; --- the generated alias ---------------------------------------------------------------------
;; A new account is named by drawing a number and reading the alias it denotes. The draw uses
;; SecureRandom, not `rand-int`: a predictable PRNG would let anyone re-derive an alias from the
;; account-creation order, which is exactly the link the alias exists to break.

(def ^:private secure-random
  "Shared CSPRNG for alias draws (`SecureRandom` is thread-safe)."
  (SecureRandom.))

(def ^:private alias-draws
  "How many fresh aliases to try before falling back to a numbered one. Five draws over a vocabulary
  of ~70 000 fail only if the corpus of taken aliases is already enormous."
  5)

(defn- draw-alias
  "A random alias in `lang`."
  [lang]
  (person-alias/alias-of (.nextInt secure-random (person-alias/alias-count lang)) lang))

(defn- alias-taken?
  "True when `e` is the **alias** uniqueness violation. One insert can violate two UNIQUE keys (the
  email, the alias); MySQL names the offending key in the message, which is the only thing that tells
  them apart — and mistaking a duplicate email for a taken alias would redraw forever."
  [e]
  (str/includes? (str (ex-message e)) "uq_user_alias"))

(defn- insert-with-alias!
  "Call `insert!` with a freshly drawn alias — `(insert! display-name alias-key)` — retrying on the
  alias uniqueness violation. The constraint is the truth: no `SELECT` first, so two concurrent
  registrations cannot both believe an alias is free. After `alias-draws` collisions the alias gets a
  numeric suffix; the last attempt lets the violation through to the caller rather than looping."
  [lang insert!]
  (loop [attempt 1]
    (let [drawn (cond-> (draw-alias lang)
                  (> attempt alias-draws) (str " " (inc (.nextInt secure-random 999))))
          last? (>= attempt (* 2 alias-draws))
          result (if last?
                   (insert! drawn (person-alias/alias-key drawn))
                   (try (insert! drawn (person-alias/alias-key drawn))
                        (catch SQLIntegrityConstraintViolationException e
                          (when-not (alias-taken? e) (throw e))
                          ::taken)))]
      (if (= ::taken result) (recur (inc attempt)) result))))

(def max-alias-length
  "Longest alias accepted. Wide enough for any drawn alias or a chosen one, short enough that the
  column and every byline stay bounded."
  60)

(defn rename-alias!
  "Rename the public alias of account `id`, and return the updated profile. Returns one of:
   - [:ok profile]
   - [:error :missing]      blank alias
   - [:error :too-long]     longer than `max-alias-length`
   - [:error :alias-taken]  another account already holds that normalized alias
   - [:error :db-error]     the database is unreachable or failing

  A name is a rectifiable field (RGPD art. 16), and the human case is the same: someone published
  under a name they no longer want. One `UPDATE` — the byline every document displays is derived
  from this row, so renaming here is what makes the corpus follow."
  [id new-alias]
  (let [nm (str/trim (or new-alias ""))]
    (cond
      (str/blank? nm) [:error :missing]
      (> (count nm) max-alias-length) [:error :too-long]
      :else (try (jdbc/execute!
                  db/ds
                  ["UPDATE AGORA_USER SET display_name = ?, alias_key = ? WHERE id = ?"
                   nm
                   (person-alias/alias-key nm)
                   id])
                 [:ok (get-user id)]
                 (catch SQLIntegrityConstraintViolationException e
                   (if (alias-taken? e) [:error :alias-taken] (throw e)))
                 (catch SQLException e
                   (core-log/error-exception e "rename-alias!: database error")
                   [:error :db-error])))))

(defn- find-by-email
  [email]
  (jdbc/execute-one!
   db/ds
   ["SELECT id, email, display_name, provider, password_hash FROM AGORA_USER WHERE email = ?" email]
   {:builder-fn rs/as-unqualified-kebab-maps}))

(defn register
  "Create a password account, publicly named by a generated alias in `lang`. Returns one of:
   - [:ok profile]            on success
   - [:error :missing]        blank email or password
   - [:error :weak-password]  password shorter than the minimum length
   - [:error :email-taken]    email already registered (incl. a lost insert race
                              on the UNIQUE constraint)
   - [:error :db-error]       the database is unreachable or failing

  The DB calls (find-by-email / insert) raise java.sql.SQLException on failure, so
  they are caught here and surfaced as a distinct error rather than bubbling up as
  an unhandled 500."
  [{:keys [email password]} lang]
  (cond
    (or (str/blank? email) (str/blank? password)) [:error :missing]
    (< (count password) min-password-length) [:error :weak-password]
    :else
    (try
      (if (find-by-email email)
        [:error :email-taken]
        (let [id (str (UUID/randomUUID))
              hash (hashers/derive password)]
          (insert-with-alias!
           lang
           (fn [display-name alias-key]
             (jdbc/execute!
              db/ds
              ["INSERT INTO AGORA_USER
                  (id, provider, email, display_name, alias_key, password_hash, created_at)
                VALUES (?, 'password', ?, ?, ?, ?, UTC_TIMESTAMP())"
               id
               email
               display-name
               alias-key
               hash])))
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
  else creates a new one, publicly named by a generated alias in `lang`. Returns
  the public profile, or nil when the database is unreachable/failing — the caller
  then treats the login as failed.

  The provider's own name and picture are **not** parameters: they identify the
  person civilly, so they are used to authenticate and then dropped.

  The DB calls raise java.sql.SQLException on failure; caught here (a UNIQUE race
  from a concurrent first login lands here too, so the user simply retries)."
  [{:keys [provider provider-id email]} lang]
  (try
    (if-let [existing (or (find-by-provider provider provider-id) (find-by-email email))]
      (get-user (:id existing))
      (let [id (str (UUID/randomUUID))]
        (insert-with-alias!
         lang
         (fn [display-name alias-key]
           (jdbc/execute!
            db/ds
            ["INSERT INTO AGORA_USER
                (id, provider, provider_id, email, display_name, alias_key, created_at)
              VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP())"
             id
             provider
             provider-id
             email
             display-name
             alias-key])))
        (get-user id)))
    (catch SQLException e (core-log/error-exception e "upsert-oauth-user: database error") nil)))
