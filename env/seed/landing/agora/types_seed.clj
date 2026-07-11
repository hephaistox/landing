(ns landing.agora.types-seed
  "Seed the epistemic KI *types* as `definition`-kind KIs (slug `type-<kind>`, major 1),
  so each type is itself described by a KI — the one the kind badge links to. This is
  the graph self-hosting its own vocabulary: `type-inference`, `type-definition`, … are
  ordinary nodes, versionable and citable like any other.

  Idempotent per (type, language): a `type-<kind>` in a language that already exists is left
  untouched, so re-running only fills gaps. Seeded in **French and English** (see
  `type-definitions.edn`); each language is a sibling KI sharing the `type-<kind>` name
  (translation-by-name), so a reader gets the definition in their own language.

  Lives in the `env/seed` environment (not `src`), added to the classpath by the
  `:env-seed` alias. Dev points at the shared **production** MySQL, so run it from a
  REPL, never a build:
    clj -M:env-dev:env-seed        ; or `bb repl` with :env-seed added
    (require 'landing.agora.types-seed)
    (landing.agora.types-seed/seed!)"
  (:require
   [clojure.edn                   :as edn]
   [clojure.java.io               :as io]
   [landing.agora.auth            :as auth]
   [landing.agora.db              :as db]
   [landing.agora.document-domain :as domain]
   [landing.agora.document-store  :as store]))

(def ^:private defs
  "kind keyword → {lang → {:title :statement}}, read from the seed resource."
  (edn/read-string (slurp (io/resource "agora/seed/type-definitions.edn"))))

(def ^:private langs
  "Languages a type-definition is seeded in — each a sibling KI sharing the `type-<kind>` name
  (translation-by-name)."
  ["fr" "en"])

(defn seed!
  "Insert a `definition` KI for every epistemic kind that doesn't yet have one, and
  clear the read caches. Returns the slugs it created (empty on a no-op re-run)."
  []
  (let [agora (auth/find-or-create-external! "Agora")
        created (doall
                 (for [{kw :id} domain/kinds
                       lang langs
                       :let [{slug :name
                              mj :major}
                             (get domain/kind-def (name kw))
                             {:keys [title statement]} (get-in defs [kw lang])]
                       ;; EXACT-language existence — `resolve-latest-id` would cross-language
                       ;; fall back (and, having just seeded `fr`, wrongly skip `en`)
                       :when (and title (not (store/lang-exists? "ki" slug mj lang)))]
                   (do (store/insert-document! {:id (store/uuid)
                                                :type "ki"
                                                :name slug
                                                :lang lang
                                                :major mj
                                                :minor 0}
                                               {:kind "definition"
                                                :title title
                                                :text statement
                                                :inputs []
                                                ;; the platform is a normal (login-less) AGORA_USER, not a nil owner
                                                :author (:display-name agora)
                                                :owner-id (:id agora)
                                                :published-at (store/now-iso)})
                       [slug lang])))]
    (store/clear-caches!)
    (vec created)))

(defn reseed!
  "Delete every type-definition KI and re-create them owned by the (login-less) \"Agora\"
  person. Use once to migrate definitions seeded before Agora became a real account — a
  plain `seed!` skips existing types, so it can't fix their nil owner. REPL-only,
  destructive; the type KIs have no inputs and aren't cited, so nothing dangles."
  []
  (doseq [{kw :id} domain/kinds
          :let [{slug :name} (get domain/kind-def (name kw))]]
    (store/q! db/ds ["DELETE FROM AGORA_DOCUMENT WHERE type = 'ki' AND name = ?" slug]))
  (store/clear-caches!)
  (seed!))
