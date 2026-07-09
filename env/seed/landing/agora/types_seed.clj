(ns landing.agora.types-seed
  "Seed the epistemic KI *types* as `definition`-kind KIs (slug `type-<kind>`, major 1),
  so each type is itself described by a KI — the one the kind badge links to. This is
  the graph self-hosting its own vocabulary: `type-inference`, `type-definition`, … are
  ordinary nodes, versionable and citable like any other.

  Idempotent: a type whose `type-<kind>` lineage already exists (in any language, per the
  engine's cross-language fallback) is left untouched, so re-running only fills gaps.
  French only for now; English titles/statements are added later via the normal
  translate flow.

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
  "kind keyword → {:title :statement} (French), read from the seed resource."
  (edn/read-string (slurp (io/resource "agora/seed/type-definitions.edn"))))

(def ^:private lang "fr")

(defn seed!
  "Insert a `definition` KI for every epistemic kind that doesn't yet have one, and
  clear the read caches. Returns the slugs it created (empty on a no-op re-run)."
  []
  (let [agora (auth/find-or-create-external! "Agora")
        created (doall
                 (for [{kw :id} domain/kinds
                       :let [{slug :name
                              mj :major}
                             (get domain/kind-def (name kw))
                             {:keys [title statement]} (get defs kw)]
                       :when (and title (nil? (store/resolve-latest-id "ki" slug mj lang)))]
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
                       slug)))]
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
