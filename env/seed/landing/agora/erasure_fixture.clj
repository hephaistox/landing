(ns landing.agora.erasure-fixture
  "Seed the corpus an account erasure is tested against by hand, in the UI.

  Erasure reattributes everything a person is attributed with to the shared anonymous person, then
  deletes their account row. What that touches is spread across three shapes a single document cannot
  cover — a byline derived from the *owner*, a byline derived from a work's *credited author*, and
  the private staging that goes with the account. So the fixture builds one of each, plus the cases
  that must **not** move: a work crediting a third person keeps its byline, a published lineage
  survives its draft being dropped, and another person's citation of an erased person's KI still
  resolves.

  What the fixture exists to prove is the **identifier**, not the name: afterwards every document
  must be attributed to the one shared anonymous id, never to an id of its own. A per-person id would
  re-link exactly what an anonymous byline pretends to have separated.

  Two password accounts are created, A (the one to erase) and B (the witness), plus a login-less
  cited person. Pass `{:a-id \"<uuid>\"}` to hang the fixture on an account already created in the
  UI instead — the way to exercise the Google path, which cannot be scripted.

  Everything a document carries goes through the real write path, so pins, inputs and bylines are
  the ones the app derives. Only the publications are named by this fixture: their title carries the
  marker `clean!` finds them by.

  Lives in the `env/seed` environment, added to the classpath by the `:env-seed` alias. Dev points at
  the shared MySQL, so run it from a REPL, never a build:
    clj -M:env-dev:env-seed
    (require 'landing.agora.erasure-fixture)
    (landing.agora.erasure-fixture/seed!)
    (landing.agora.erasure-fixture/clean!)"
  (:require
   [clojure.string                     :as str]
   [landing.agora.admin                :as admin]
   [landing.agora.auth                 :as auth]
   [landing.agora.db                   :as db]
   [landing.agora.db.document          :as db-doc]
   [landing.agora.document.cached-db   :as dcd]
   [landing.agora.document.identity    :as di]
   [landing.agora.document.publication :as publication]
   [landing.agora.document.write       :as write]
   [next.jdbc                          :as jdbc]
   [next.jdbc.result-set               :as rs]))

(def marker
  "Title prefix every fixture publication carries. What `clean!` recognizes the fixture by, so a
  corpus it shares with real documents can still be swept."
  "Fixture effacement")

(def ^:private email-domain
  "Reserved TLD the fixture accounts are addressed in — never deliverable, and the second thing
  `clean!` recognizes its own people by."
  "erasure-fixture.test")

(def ^:private account-password
  "Password both fixture accounts are given, to log in with in the UI."
  "fixture-motdepasse")

(def ^:private cited-person
  "The login-less person a work cites. Their byline must survive A's erasure untouched."
  "Camille Citée (fixture)")

;; --- people ---------------------------------------------------------------

(defn- account-id
  "Id of the account registered under `email`, or nil."
  [email]
  (:id (jdbc/execute-one! db/ds
                          ["SELECT id FROM AGORA_USER WHERE email = ?" email]
                          {:builder-fn rs/as-unqualified-kebab-maps})))

(defn- ensure-account!
  "Id of the fixture account `local`@<domain>, registering it on first use. Re-running the seed
  reuses the account rather than colliding on the email."
  [local]
  (let [email (str local "@" email-domain)]
    (or (account-id email)
        (let [[status profile] (auth/register {:email email
                                               :password account-password}
                                              :fr)]
          (when-not (= :ok status)
            (throw (ex-info "fixture: registration failed"
                            {:email email
                             :error profile})))
          (:id profile)))))

;; --- documents ------------------------------------------------------------

(defn- doc!
  "Create one document through the write path and return what the fixture needs of it — its row id,
  its minted cid, and enough identity to cite it and to print its URL."
  [type owner-id fields pub-cid]
  (let [id (write/create! type owner-id fields pub-cid)
        {:keys [name lang major]} (db-doc/fetch-id id)]
    {:id id
     :cid name
     :lang lang
     :major major
     :type type
     :title (:title fields)}))

(defn- cite
  "The in-prose citation of `doc` — what makes it an input of the document quoting it."
  [{:keys [cid major]}]
  (str "[[ki:" cid "@" major "]]"))

(defn- url
  "Public permalink of `doc`, to paste in the browser."
  [{:keys [type cid lang major title]}]
  (str "/agora/" (name lang) "/" (name type) "/" (di/permalink-slug cid title) "/" major))

(defn- publication!
  "Open a publication owned by `owner-id`, titled with the fixture marker. Returns its cid — the
  view names it `:id`, since that is the identity references survive a rename by."
  [owner-id label]
  (or (:id (publication/create! owner-id (str marker " — " label)))
      (throw (ex-info "fixture: publication was not created" {:owner-id owner-id}))))

(defn- publish!
  "Publish publication `cid` on behalf of `owner-id`. Throws rather than returning nil: a publication
  that silently stays open would leave every document it gathers a draft, and the fixture would look
  built while none of its permalinks resolve."
  [owner-id cid]
  (or (publication/publish! owner-id cid)
      (throw (ex-info "fixture: publication was not published"
                      {:owner-id owner-id
                       :cid cid}))))

;; --- seed -----------------------------------------------------------------

(defn seed!
  "Build the fixture and return the map to run the manual test from: credentials, the account to
  erase, and every URL to open before and after.

  Titles say what each document is *for*, since the test is read off the pages themselves.

  Order is what makes it valid — the erased account's publication is published before the witness
  cites one of its documents, since a publication never resolves another one's drafts. Its second
  publication is left **open**: it is the private staging erasure destroys, and it holds a draft edit
  of an already-published document, the one place a deletion comes near published content."
  ([] (seed! {}))
  ([{:keys [a-id]}]
   (let
     [erased (or a-id (ensure-account! "a"))
      witness (ensure-account! "b")
      third-party (:id (auth/find-or-create-external! cited-person))
      ;; the erased account publishes: a claim, an article citing it, a work credited to a third party
      pub-erased (publication! erased "publication close du compte à effacer")
      claim
      (doc!
       :ki
       erased
       {:kind :assumption
        :lang :fr
        :title (str marker " — connaissance publiée par le compte à effacer")
        :text
        "Signée par le compte à effacer et citée ailleurs : elle doit rester en ligne après l'effacement, signée « Anonymus »."}
       pub-erased)
      article (doc! :article
                    erased
                    {:kind :explainer
                     :lang :fr
                     :title (str marker " — article publié par le compte à effacer")
                     :text (str "Cet article cite "
                                (cite claim)
                                " : la citation doit continuer à résoudre après l'effacement.")}
                    pub-erased)
      work-third-party
      (doc! :ki
            erased
            {:kind :work
             :lang :fr
             :title (str marker " — ouvrage créé par le compte à effacer, crédité à un tiers")
             :text ""
             :author-id third-party
             :year "1840"
             :editor "Fixture"}
            pub-erased)
      _ (publish! erased pub-erased)
      ;; the witness publishes: a work credited to the erased account, and a claim citing its claim
      pub-witness (publication! witness "publication close du témoin")
      work-credited (doc! :ki
                          witness
                          {:kind :work
                           :lang :fr
                           :title (str marker
                                       " — ouvrage créé par le témoin, crédité au compte à effacer")
                           :text ""
                           :author-id erased
                           :year "1946"
                           :editor "Fixture"}
                          pub-witness)
      claim-witness
      (doc! :ki
            witness
            {:kind :deduction
             :lang :fr
             :title (str marker " — connaissance du témoin, citant celle du compte à effacer")
             :text (str "Déduite de "
                        (cite claim)
                        " : l'arête doit survivre à l'effacement de son auteur.")}
            pub-witness)
      _ (publish! witness pub-witness)
      ;; the erased account's staging, left open — this is what erasure destroys
      pub-open (publication! erased "publication ouverte du compte à effacer")
      draft-new (doc! :ki
                      erased
                      {:kind :assumption
                       :lang :fr
                       :title (str marker " — brouillon jamais publié du compte à effacer")
                       :text "Jamais publié : il doit disparaître avec le compte."}
                      pub-open)
      draft-edit
      (write/edit!
       (:id claim)
       erased
       {:title (str marker " — brouillon de modification de la connaissance publiée")
        :text
        "Édition en brouillon : elle doit disparaître sans emporter la version publiée de la connaissance."}
       pub-open)]
     (admin/reconcile!)
     (dcd/clear!)
     {:account-to-erase {:login (when-not a-id (str "a@" email-domain))
                         :password (when-not a-id account-password)
                         :person-id erased
                         :display-name (auth/display-name erased)}
      :witness-account {:login (str "b@" email-domain)
                        :password account-password
                        :person-id witness
                        :display-name (auth/display-name witness)}
      :third-party {:person-id third-party
                    :display-name cited-person}
      :urls {:claim (url claim)
             :article (url article)
             :work-credited-to-third-party (url work-third-party)
             :work-credited-to-erased (url work-credited)
             :claim-of-witness (url claim-witness)
             :unpublished-draft (url draft-new)
             :author-page (str "/agora/fr/author/" erased)
             :publication-closed (str "/agora/fr/publication/" pub-erased)
             :publication-open (str "/agora/fr/publication/" pub-open)}
      :expected
      {"attribués à l'id anonyme partagé après effacement"
       [(url claim) (url article) (url work-credited) (str "/agora/fr/publication/" pub-erased)]
       "signature inchangée (le tiers cité)" [(url work-third-party)]
       "404 après effacement" [(url draft-new) (str "/agora/fr/publication/" pub-open)]
       "intact après effacement" [(url claim) (url claim-witness)]}
      :anonymous-id auth/anonymous-id
      :draft-ids {:draft-edit draft-edit
                  :unpublished-draft (:id draft-new)}})))

;; --- clean ----------------------------------------------------------------

(defn- fixture-publications
  "The publications this fixture created — the ones whose title carries the marker."
  []
  (filterv #(str/starts-with? (str (:title %)) marker)
           (db-doc/latest-page {:type :publication
                                :drafts? true})))

(defn- disposable-person?
  "Whether person `id` may be deleted: a fixture account, or the person the fixture credits. An
  account created in the UI and passed as `:a-id` is left alone — the fixture never removes someone's
  live account. An erased account has no row left to recognize."
  [id]
  (some?
   (jdbc/execute-one!
    db/ds
    ["SELECT 1 FROM AGORA_USER
        WHERE id = ? AND (email LIKE ? OR display_name = ?) LIMIT 1"
     id
     (str "%@" email-domain)
     cited-person])))

(defn clean!
  "Remove the fixture: the documents its publications gather (published ones included), the
  publications, then the people it created. Reconciles, so the successor edges of what it deleted go
  with it. Returns what was removed. Idempotent — a second run finds nothing."
  []
  (let [pubs (fixture-publications)
        cids (mapv :name pubs)
        docs (into [] (mapcat publication/documents) cids)
        people
        (into #{} (comp (mapcat (juxt :owner-id :author-id)) (remove nil?)) (concat pubs docs))]
    (doseq [cid cids]
      (jdbc/execute! db/ds ["DELETE FROM AGORA_DOCUMENT WHERE publication_id = ?" cid])
      (jdbc/execute! db/ds
                     ["DELETE FROM AGORA_DOCUMENT WHERE type = 'publication' AND name = ?" cid]))
    (let [removed (filterv disposable-person? people)]
      ;; a person still attributed with something is refused by the foreign key, which is the rule to
      ;; rely on rather than work around: the documents above went first, so what this skips is a
      ;; fixture account something outside the fixture came to point at
      (doseq [id removed]
        (jdbc/execute!
         db/ds
         ["DELETE FROM AGORA_USER
                           WHERE id = ?
                             AND NOT EXISTS (SELECT 1 FROM AGORA_DOCUMENT d
                                              WHERE d.owner_id = ? OR d.author_id = ?)"
          id
          id
          id]))
      (admin/reconcile!)
      (dcd/clear!)
      {:publications (count cids)
       :documents (count docs)
       :people (count removed)})))
