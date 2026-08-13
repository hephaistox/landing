(ns landing.agora.types-seed
  "Seed the epistemic KI *types* as `definition`-kind KIs (slug `type-<kind>`, major 1),
  so each type is itself described by a KI — the one the kind badge links to. This is
  the graph self-hosting its own vocabulary: `type-inference`, `type-definition`, … are
  ordinary nodes, versionable and citable like any other.

  Idempotent per (type, language): a `type-<kind>` in a language that already exists is left
  untouched, so re-running only fills gaps. Seeded in **French and English** (see
  `type-definitions.edn`); each language is a sibling KI sharing the `type-<kind>` name
  (translation-by-name), so a reader gets the definition in their own language.

  The type definitions are gathered in one published publication, `vocabulaire-des-types`, so
  the vocabulary carries the same provenance any authored document does — a closed work-package
  the KIs point at. Each type KI is written with one direct INSERT (empty pins, this publication)
  rather than through the general write path, since a type-definition has no inputs.

  Lives in the `env/seed` environment (not `src`), added to the classpath by the
  `:env-seed` alias. Dev points at the shared **production** MySQL, so run it from a
  REPL, never a build:
    clj -M:env-dev:env-seed        ; or `bb repl` with :env-seed added
    (require 'landing.agora.types-seed)
    (landing.agora.types-seed/seed!)"
  (:require
   [clojure.edn                      :as edn]
   [clojure.java.io                  :as io]
   [landing.agora.auth               :as auth]
   [landing.agora.db                 :as db]
   [landing.agora.document.cached-db :as dcd]
   [landing.agora.document.kind      :as dk]
   [next.jdbc                        :as jdbc])
  (:import (java.time Instant)
           (java.time.temporal ChronoUnit)
           (java.util UUID)))

(def ^:private defs
  "kind keyword → {lang → {:title :statement}}, read from the seed resource."
  (edn/read-string (slurp (io/resource "agora/seed/type-definitions.edn"))))

(def ^:private langs
  "Languages a type-definition is seeded in — each a sibling KI sharing the `type-<kind>` name
  (translation-by-name)."
  ["fr" "en"])

(def ^:private pub-cid
  "Stable cid of the publication gathering the type vocabulary — readable, like the type slugs."
  "vocabulaire-des-types")

(def ^:private pub-lang
  "A publication has no content language; its NOT-NULL `lang` column holds the neutral placeholder."
  "zz")

(defn- now-iso
  "Current UTC instant as a sortable second-resolution ISO-8601 string (matches `published_at`)."
  []
  (str (.truncatedTo (Instant/now) ChronoUnit/SECONDS)))

(defn- lang-exists?
  "True when a `type-<slug>` KI already exists in `lang` at `major`. Exact-language check —
  a cross-language fallback would, having just seeded `fr`, wrongly skip `en`."
  [slug major lang]
  (some?
   (jdbc/execute-one!
    db/ds
    ["SELECT 1 FROM AGORA_DOCUMENT WHERE type = 'ki' AND name = ? AND major = ? AND lang = ? LIMIT 1"
     slug
     major
     lang])))

(defn- ensure-publication!
  "Insert the closed `vocabulaire-des-types` publication if it is absent, owned by the \"Agora\"
  person. Idempotent: a re-run leaves an existing one untouched. The vocabulary is published, so the
  publication is `:closed` (draft 0)."
  [author owner-id published-at]
  (when-not (jdbc/execute-one!
             db/ds
             ["SELECT 1 FROM AGORA_DOCUMENT WHERE type = 'publication' AND name = ? LIMIT 1"
              pub-cid])
    (jdbc/execute!
     db/ds
     ["INSERT INTO AGORA_DOCUMENT
         (id, type, name, lang, major, minor, draft, content, computed, published_at)
       VALUES (?, 'publication', ?, ?, 1, 0, 0, ?, ?, ?)"
      (str (UUID/randomUUID))
      pub-cid
      pub-lang
      (pr-str {:title "Vocabulaire des types"
               :status :closed
               :author author
               :owner-id owner-id
               :published-at published-at})
      (pr-str {:pins []})
      published-at])))

(defn- insert!
  "Insert one published `definition` KI directly, gathered in the `vocabulaire-des-types` publication:
  immutable `content`, empty `:pins`. A type-definition has no inputs, so nothing to pin and no
  successor edge."
  [{:keys [slug lang major title statement author owner-id published-at]}]
  (jdbc/execute!
   db/ds
   ["INSERT INTO AGORA_DOCUMENT
       (id, type, name, lang, major, minor, draft, content, computed, published_at, publication_id)
     VALUES (?, 'ki', ?, ?, ?, 0, 0, ?, ?, ?, ?)"
    (str (UUID/randomUUID))
    slug
    lang
    major
    (pr-str {:kind "definition"
             :title title
             :text statement
             :inputs []
             :author author
             :owner-id owner-id
             :published-at published-at})
    (pr-str {:pins {}})
    published-at
    pub-cid]))

(defn seed!
  "Insert a `definition` KI for every epistemic kind that doesn't yet have one, owned by the
  (login-less) \"Agora\" person, then clear the read caches. Returns the `[slug lang]` pairs it
  created (empty on a no-op re-run)."
  []
  (let [agora (auth/find-or-create-external! "Agora")
        now (now-iso)
        _ (ensure-publication! (:display-name agora) (:id agora) now)
        created (doall (for [{kw :id} dk/kinds
                             lang langs
                             :let [{slug :name
                                    mj :major}
                                   (get dk/kind-def kw)
                                   {:keys [title statement]} (get-in defs [kw lang])]
                             :when (and title (not (lang-exists? slug mj lang)))]
                         (do (insert! {:slug slug
                                       :lang lang
                                       :major mj
                                       :title title
                                       :statement statement
                                       :author (:display-name agora)
                                       :owner-id (:id agora)
                                       :published-at now})
                             [slug lang])))]
    (dcd/clear!)
    (vec created)))

(defn reseed!
  "Delete every type-definition KI and re-create them owned by the (login-less) \"Agora\"
  person. Use to fix definitions seeded wrongly — a plain `seed!` skips existing types.
  REPL-only, destructive; the type KIs have no inputs and aren't cited, so nothing dangles."
  []
  (doseq [{kw :id} dk/kinds
          :let [{slug :name} (get dk/kind-def kw)]]
    (jdbc/execute! db/ds ["DELETE FROM AGORA_DOCUMENT WHERE type = 'ki' AND name = ?" slug]))
  (jdbc/execute! db/ds
                 ["DELETE FROM AGORA_DOCUMENT WHERE type = 'publication' AND name = ?" pub-cid])
  (dcd/clear!)
  (seed!))
