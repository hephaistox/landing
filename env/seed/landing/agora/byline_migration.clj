(ns landing.agora.byline-migration
  "One-shot pass moving the byline **name** out of every version's immutable `content` and into its
  derived `computed`, where the reconcile can repair it.

  A name copied into `content` cannot be rectified or erased: renaming a person, or erasing their
  account, would leave the old name displayed and physically stored. So `content` keeps only who
  (`:owner-id`, and a work's cited `:author-id`) and `computed` caches the name that person's
  AGORA_USER row currently carries.

  The name is **re-derived**, not moved verbatim — one rule, `AGORA_USER.display_name` of
  `(or :author-id :owner-id)` — so a name that had drifted from its person is corrected on the way.
  Idempotent: a version whose `content` no longer holds `:author` is left untouched.

  Lives in the `env/seed` environment (not `src`), added to the classpath by the `:env-seed` alias.
  Dev points at the shared MySQL, so run it from a REPL, never a build:
    clj -M:env-dev:env-seed        ; or `bb repl` with :env-seed added
    (require 'landing.agora.byline-migration)
    (landing.agora.byline-migration/migrate!)"
  (:require
   [clojure.edn                      :as edn]
   [landing.agora.auth               :as auth]
   [landing.agora.db                 :as db]
   [landing.agora.db.document        :as db-doc]
   [landing.agora.document.cached-db :as dcd]
   [landing.agora.document.kind      :as dk]
   [next.jdbc                        :as jdbc]
   [next.jdbc.result-set             :as rs]))

(def ^:private kebab {:builder-fn rs/as-unqualified-kebab-maps})

(defn- decode
  [s]
  (or (some-> s
              edn/read-string)
      {}))

(defn- move
  "The rewrite of one row, or nil when its `content` holds no `:author` (already moved): the content
  stripped of the name, and the whole derived blob rebuilt — the pins it already had, plus the name
  `people` gives for the person the content is attributed to."
  [people {:keys [id content computed]}]
  (let [content (decode content)
        computed (decode computed)]
    (when (contains? content :author)
      {:id id
       :was (:author content)
       :content (dissoc content :author)
       :computed (db-doc/computed (:pins computed)
                                  (get people (dk/attributed-author-id content)))})))

(defn migrate!
  "Rewrite every version whose `content` still holds a byline name, then clear the read caches.
  Returns `{:moved n :renamed [{:id :was :now}…]}` — `:renamed` lists the versions whose displayed
  name changed, i.e. the copies that had drifted from the person they name."
  []
  (let [people (auth/display-names)
        fixes (into
               []
               (keep #(move people %))
               (jdbc/execute! db/ds ["SELECT id, content, computed FROM AGORA_DOCUMENT"] kebab))]
    (when (seq fixes)
      (with-open [conn (jdbc/get-connection db/ds)]
        (jdbc/execute-batch!
         conn
         "UPDATE AGORA_DOCUMENT SET content = ?, computed = ? WHERE id = ?"
         (mapv (fn [{:keys [id content computed]}] [(pr-str content) (pr-str computed) id]) fixes)
         {}))
      (dcd/clear!))
    {:moved (count fixes)
     :renamed (into []
                    (keep (fn [{:keys [id was computed]}]
                            (when (not= was (:author computed))
                              {:id id
                               :was was
                               :now (:author computed)})))
                    fixes)}))
