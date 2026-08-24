(ns landing.agora.auth
  "User accounts and email/password authentication.

  Passwords are hashed with bcrypt (buddy-hashers) and never stored or returned
  in clear. Transport security is HTTPS (Clever Cloud in prod). The user model
  also carries `provider`/`provider_id` so OAuth accounts slot in later.

  **No account ever carries a civil identity.** The public name is a display name drawn
  here at creation, for every provider — what Google hands us (a real name, a
  photo) is used to authenticate and then dropped. On a platform whose subject
  matter is political, philosophical and religious positions, the default cannot
  be the user's legal name."
  (:require
   [auto-core.log                     :as core-log]
   [buddy.hashers                     :as hashers]
   [clojure.string                    :as str]
   [landing.agora.db                  :as db]
   [landing.agora.person.display-name :as person-name]
   [next.jdbc                         :as jdbc]
   [next.jdbc.result-set              :as rs])
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

(def anonymous-id
  "The one person every erased account is absorbed into, seeded with the schema.

  Shared, not one per erasure: an identifier that gathers exactly one person's documents is personal
  data however anonymous the name on it reads — and this one was public for as long as the account
  lived, so anyone who read the corpus holds the mapping. What survives an erasure must therefore
  group every erased person together and single out none.

  Fixed and obviously synthetic, so it is recognizable on sight in a response, a log or a dump."
  "00000000-0000-0000-0000-000000000000")

(def platform-id
  "The platform itself, as a person: what the seeded corpus belongs to — the epistemic type
  definitions, and the FAQ next.

  Not anonymous, and the distinction is the point: Agora is an **identified** author, accountable for
  what it signed and answerable for it, where `anonymous-id` is the absence of one. They occupy the
  same slot and mean opposite things.

  Fixed rather than minted by whichever seed runs first, so every environment agrees on who owns the
  platform's own content and code can name it."
  "00000000-0000-0000-0000-000000000001")

(def reserved-ids
  "The people the schema seeds and the corpus is built on. Neither is an account: nobody can log in
  as them, so nothing reaches them through the API today. The rule is held here anyway, because the
  failure it guards against is silent — erasure reattributes *before* it deletes, so the foreign key
  that protects every other row would let these two go, taking the seeded corpus to the anonymous
  bucket on the way out."
  #{anonymous-id platform-id})

(defn reserved?
  "Whether `person-id` is one of the seeded platform people, who may not be erased."
  [person-id]
  (contains? reserved-ids person-id))

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

(defn member?
  "Whether a person is an **account** — someone who signed up and writes here — rather than one of the
  login-less people the corpus merely cites (an author credited on a work, and the two seeded rows).
  Both live in AGORA_USER and both sign documents, so `provider` is the only thing that tells them
  apart.

  Derived rather than served raw: which of Google or a password someone signs in with is theirs, and
  a public list has no business publishing it."
  [provider]
  (not= "external" provider))

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

(defn sees-drafts-of?
  "Whether `viewer-id` may see `owner-id`'s unpublished work: the owner themselves, or the platform
  owner. The one rule, so the listing that offers a draft and the read that opens it cannot disagree
  — an administrator shown a card they may not open is worse than not being shown it.

  The last clause of the visibility rule (agora/CLAUDE.md, \"Who sees what\"), which holds on every
  screen: everyone sees what they made, everyone sees what is published attributed to its author, a
  withdrawn author changes the byline and not the visibility, and a draft is its author's and the
  platform owner's alone.

  The administrator's reach here is the same one they already have over publications, where the index
  hands them other owners' open ones. Short-circuits on the owner, so the ordinary case costs no
  query."
  [viewer-id owner-id]
  (boolean (and viewer-id (or (= viewer-id owner-id) (:admin (get-user viewer-id))))))

(defn author-profile
  "The *public author card* for user `id`, or nil: display name, avatar and the
  account-creation date. Deliberately excludes email and any private field — this is
  shown on the public author page, to anyone.

  Carries `:member?`, since the same page serves both an account and a login-less person the corpus
  merely credits, and the difference is the first thing a reader wants: one answers for what they
  wrote, the other never claimed it.

  An erased person has no row, so no page: their documents were reattributed to the shared anonymous
  person, whose own page is the one hub they all share."
  [id]
  (some-> (jdbc/execute-one!
           db/ds
           ["SELECT id, display_name, avatar_url, created_at, provider FROM AGORA_USER WHERE id = ?"
            id]
           {:builder-fn rs/as-unqualified-kebab-maps})
          (as-> row (-> row
                        (dissoc :provider)
                        (assoc :member? (member? (:provider row)))))))

(defn display-name
  "The public name of person `id`, or nil when unknown. The single source of every byline: a
  document caches it in its derived `computed`, so a rename or an erasure of this row propagates."
  [id]
  (when id
    (:display-name (jdbc/execute-one! db/ds
                                      ["SELECT display_name FROM AGORA_USER WHERE id = ?" id]
                                      {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn- person-card
  "A person as the public surface shows them: who they are, what to display, and whether they are a
  member."
  [row]
  {:id (:id row)
   :display-name (:display-name row)
   :member? (member? (:provider row))})

(defn display-names
  "Every person as `id → display_name`. One query, so the reconcile can derive the whole corpus's
  bylines without a lookup per document."
  []
  (into {}
        (map (juxt :id :display-name))
        (jdbc/execute! db/ds
                       ["SELECT id, display_name FROM AGORA_USER"]
                       {:builder-fn rs/as-unqualified-kebab-maps})))

(defn people-index
  "Every person as `id → {:id :display-name :member?}` — one query, for a caller that holds ids and
  needs cards for them."
  []
  (into {}
        (map (juxt :id person-card))
        (jdbc/execute! db/ds
                       ["SELECT id, display_name, provider FROM AGORA_USER"]
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
  {:id :display-name}.

  Reaches the shared anonymous person like any other, so a work credited to an author nobody knows
  lands on the one row that already means exactly that. One name, one row: creating a homonym beside
  it would give the picker two entries no reader could tell apart."
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
  picker (both real accounts and external cited people, each carrying `:member?`). Blank `q` → [].

  The shared anonymous person is offered like anyone else: \"no known author\" is an ordinary
  bibliographic attribution, and pointing such a work at the row that already carries the erased is
  not a conflation to avoid but the same statement twice — nobody knows who wrote this. A wider,
  more mixed set of documents under that one id is also a poorer thing to correlate than a set made
  only of erased accounts."
  [q]
  (if (str/blank? q)
    []
    (mapv
     person-card
     (jdbc/execute!
      db/ds
      ["SELECT id, display_name, provider FROM AGORA_USER
        WHERE display_name LIKE ? ORDER BY display_name LIMIT 20"
       (str "%" (db/escape-like (str/trim q)) "%")]
      {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn set-lang!
  "Persist the user's preferred interface language, and return the updated public
  profile."
  [id lang]
  (jdbc/execute! db/ds ["UPDATE AGORA_USER SET lang = ? WHERE id = ?" lang id])
  (get-user id))

;; --- the generated display name ---------------------------------------------------------------------
;; A new account is named by drawing a number and reading the display name it denotes. The draw uses
;; SecureRandom, not `rand-int`: a predictable PRNG would let anyone re-derive a display name from the
;; account-creation order, which is exactly the link the display name exists to break.

(def ^:private secure-random
  "Shared CSPRNG for display name draws (`SecureRandom` is thread-safe)."
  (SecureRandom.))

(def ^:private display-name-draws
  "How many fresh display names to try before falling back to a numbered one. Five draws over a vocabulary
  of ~70 000 fail only if the corpus of taken display names is already enormous."
  5)

(defn- draw-display-name
  "A random display name in `lang`."
  [lang]
  (person-name/display-name-of (.nextInt secure-random (person-name/display-name-count lang)) lang))

(defn- display-name-taken?
  "True when `e` is the **display name** uniqueness violation. One insert can violate two UNIQUE keys (the
  email, the display name); MySQL names the offending key in the message, which is the only thing that tells
  them apart — and mistaking a duplicate email for a taken display name would redraw forever."
  [e]
  (str/includes? (str (ex-message e)) "uq_user_display_name"))

(defn- insert-with-display-name!
  "Call `insert!` with a freshly drawn display name — `(insert! display-name display-name-key)` — retrying on the
  display name uniqueness violation. The constraint is the truth: no `SELECT` first, so two concurrent
  registrations cannot both believe a display name is free. After `display-name-draws` collisions the display name gets a
  numeric suffix; the last attempt lets the violation through to the caller rather than looping."
  [lang insert!]
  (loop [attempt 1]
    (let [drawn (cond-> (draw-display-name lang)
                  (> attempt display-name-draws) (str " " (inc (.nextInt secure-random 999))))
          last? (>= attempt (* 2 display-name-draws))
          result (if last?
                   (insert! drawn (person-name/display-name-key drawn))
                   (try (insert! drawn (person-name/display-name-key drawn))
                        (catch SQLIntegrityConstraintViolationException e
                          (when-not (display-name-taken? e) (throw e))
                          ::taken)))]
      (if (= ::taken result) (recur (inc attempt)) result))))

(def max-display-name-length
  "Longest display name accepted. Wide enough for any drawn display name or a chosen one, short enough that the
  column and every byline stay bounded."
  60)

(defn rename-display-name!
  "Rename the public display name of account `id`, and return the updated profile. Returns one of:
   - [:ok profile]
   - [:error :missing]      blank display name
   - [:error :too-long]     longer than `max-display-name-length`
   - [:error :display-name-taken]  another account already holds that normalized display name
   - [:error :db-error]     the database is unreachable or failing

  A name is a rectifiable field (RGPD art. 16), and the human case is the same: someone published
  under a name they no longer want. One `UPDATE` — the byline every document displays is derived
  from this row, so renaming here is what makes the corpus follow."
  [id new-display-name]
  (let [nm (str/trim (or new-display-name ""))]
    (cond
      (str/blank? nm) [:error :missing]
      (> (count nm) max-display-name-length) [:error :too-long]
      :else (try (jdbc/execute!
                  db/ds
                  ["UPDATE AGORA_USER SET display_name = ?, display_name_key = ? WHERE id = ?"
                   nm
                   (person-name/display-name-key nm)
                   id])
                 [:ok (get-user id)]
                 (catch SQLIntegrityConstraintViolationException e
                   (if (display-name-taken? e) [:error :display-name-taken] (throw e)))
                 (catch SQLException e
                   (core-log/error-exception e "rename-display-name!: database error")
                   [:error :db-error])))))

(defn delete-account!
  "Delete person `id`'s account row. Returns true when a row went.

  The whole row, not a blanked placeholder: what identifies someone is not only the name on the
  account but the identifier every document pointed at, and that identifier was public. Nothing may
  reference `id` when this runs — the foreign key on the documents refuses otherwise, which is
  exactly the guarantee wanted: the row can only go once its documents have been reattributed.

  Registering with the same address afterwards creates a **new**, unrelated account: there is
  nothing left for `find-by-provider` or `find-by-email` to match."
  [id]
  (pos? (or (::jdbc/update-count (jdbc/execute-one! db/ds
                                                    ["DELETE FROM AGORA_USER WHERE id = ?" id]))
            0)))

(defn- find-by-email
  [email]
  (jdbc/execute-one!
   db/ds
   ["SELECT id, email, display_name, provider, password_hash FROM AGORA_USER WHERE email = ?" email]
   {:builder-fn rs/as-unqualified-kebab-maps}))

(defn register
  "Create a password account, publicly named by a generated display name in `lang`. Returns one of:
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
          (insert-with-display-name!
           lang
           (fn [display-name display-name-key]
             (jdbc/execute!
              db/ds
              ["INSERT INTO AGORA_USER
                  (id, provider, email, display_name, display_name_key, password_hash, created_at)
                VALUES (?, 'password', ?, ?, ?, ?, UTC_TIMESTAMP())"
               id
               email
               display-name
               display-name-key
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
  else creates a new one, publicly named by a generated display name in `lang`. Returns
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
        (insert-with-display-name!
         lang
         (fn [display-name display-name-key]
           (jdbc/execute!
            db/ds
            ["INSERT INTO AGORA_USER
                (id, provider, provider_id, email, display_name, display_name_key, created_at)
              VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP())"
             id
             provider
             provider-id
             email
             display-name
             display-name-key])))
        (get-user id)))
    (catch SQLException e (core-log/error-exception e "upsert-oauth-user: database error") nil)))
