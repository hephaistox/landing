(ns art-of-war-seed
  "One-off seeding of the Agora graph from a public-domain source text.

  Currently: Sun Tzŭ's *The Art of War* (Lionel Giles' 1910 translation, Project
  Gutenberg #132, public domain), parsed one KI per numbered verse into
  `resources/agora/seed/art-of-war.edn`. Each KI is stamped with:

   - `:author \"Sun Tzŭ\"` — the reasoning's author (shown on the page, fed to
     schema.org). Distinct from `:owner-id`, the accountable account that can edit.
   - a structured `:source` citation map (work / translator / verse ref / license)
     preserved in the immutable content for later official quoting.
   - a `name` slug prefixed `aow-<chapter>-<verse>` — the durable, queryable marker
     for this source (`… WHERE name LIKE 'aow-%'`).

  Meant to be run from a REPL against the DB, not wired into the app. Nothing here
  runs automatically. All writes go through `node/insert-node!` so pins + the
  successor index stay consistent; `AGORA_USER` is never touched."
  (:require
   [clojure.edn        :as edn]
   [clojure.java.io    :as io]
   [landing.agora.auth :as auth]
   [landing.agora.db   :as db]
   [landing.agora.node :as node]))

(def ^:private ki-type "ki")
(def ^:private source-prefix "aow-")
(def ^:private author "Sun Tzŭ")

(def ^:private curated-edges
  "A hand-curated seed of input edges — {conclusion-slug [premise-slug …]} — so the
  graph reads as a graph (and each conclusion's schema.org `isBasedOn` lists its
  premises). Two chains: Chapter I's five-factors lineage, and the famous III.18.
  Edited by hand; every slug is verified to exist at load time."
  {"aow-1-4" ["aow-1-3"]
   "aow-1-5-6" ["aow-1-4"]
   "aow-1-7" ["aow-1-4"]
   "aow-1-8" ["aow-1-4"]
   "aow-1-9" ["aow-1-4"]
   "aow-1-10" ["aow-1-4"]
   "aow-1-11" ["aow-1-5-6" "aow-1-7" "aow-1-8" "aow-1-9" "aow-1-10"]
   "aow-1-14" ["aow-1-13"]
   "aow-3-18" ["aow-3-17"]})

(defn- input-tnlr
  [premise-slug]
  {:type ki-type
   :name premise-slug
   :lang "en"
   :major 1})

(defn- attach-edges
  "Give each KI its declared `:inputs` (TNLRs) from `curated-edges`, else []. Throws
  if an edge references a slug not present in the seed (a curation typo)."
  [kis]
  (let [slugs (set (map :name kis))]
    (doseq [[concl premises] curated-edges
            slug (cons concl premises)]
      (assert (slugs slug) (str "curated-edges references unknown slug: " slug)))
    (mapv (fn [k] (assoc k :inputs (mapv input-tnlr (get curated-edges (:name k) [])))) kis)))

(defn- topo-order
  "Order KIs so every KI is inserted after the KIs it declares as inputs (DFS
  post-order over the intra-seed edges). insert-node! resolves each input's pin by
  looking up its latest id, so premises must already exist. Throws on a cycle."
  [kis]
  (let [by-name (into {} (map (juxt :name identity)) kis)
        seen (volatile! #{})
        path (volatile! #{})
        out (volatile! [])]
    (letfn [(visit [nm]
              (when (and (by-name nm) (not (@seen nm)))
                (when (@path nm) (throw (ex-info "cycle in curated-edges" {:at nm})))
                (vswap! path conj nm)
                (doseq [in (:inputs (by-name nm))] (visit (:name in)))
                (vswap! path disj nm)
                (vswap! seen conj nm)
                (vswap! out conj (by-name nm))))]
      (doseq [k kis] (visit (:name k))) @out)))

(defn art-of-war
  "The parsed seed: {:meta {…} :kis [{:name :lang :kind :title :statement :source} …]}."
  []
  (edn/read-string (slurp (io/resource "agora/seed/art-of-war.edn"))))

(defn resolve-owner-id
  "The platform owner's account id (first allow-listed admin email present in
  AGORA_USER), or nil if that account hasn't signed in yet. Used as `:owner-id`
  for the seed so the KIs are owned by a real, accountable account."
  []
  (some->> (seq auth/admin-emails)
           (map #(first (node/q! db/ds ["SELECT id FROM AGORA_USER WHERE email = ?" %] node/kebab)))
           (keep :id)
           first))

(defn delete-art-of-war!
  "Remove every KI from this source (matched by the `aow-` name marker) and rebuild
  the successor index. Idempotent; the reseed calls this first. Returns rows removed."
  []
  (let [n (-> (node/q! db/ds
                       ["DELETE FROM AGORA_NODE WHERE type = ? AND name LIKE ?"
                        ki-type
                        (str source-prefix "%")])
              first
              :next.jdbc/update-count)]
    (node/rebuild-successor-index!)
    n))

(defn seed-art-of-war!
  "Insert all Art-of-War KIs owned by `owner-id` (default: the resolved platform
  owner), wired with the `curated-edges`. Clears any existing `aow-` KIs first, so it
  is safe to re-run. Inserts in topological order so each edge's pin resolves.
  Returns {:inserted n :edges m}. Throws if no owner-id can be resolved — pass one."
  ([] (seed-art-of-war! (resolve-owner-id)))
  ([owner-id]
   (assert owner-id "No owner-id: pass one, or have the admin account sign in first.")
   (delete-art-of-war!)
   (let [kis (topo-order (attach-edges (:kis (art-of-war))))]
     (doseq [{:keys [name lang kind title statement source inputs]} kis]
       (node/insert-node! {:id (node/uuid)
                           :type ki-type
                           :name name
                           :lang (or lang "en")
                           :major 1
                           :minor 0}
                          {:kind kind
                           :title title
                           :statement statement
                           :inputs inputs
                           :author author
                           :owner-id owner-id
                           :published-at (node/now-iso)
                           :source source}))
     (node/rebuild-successor-index!)
     {:inserted (count kis)
      :edges (reduce + (map (comp count val) curated-edges))})))

(defn wipe-kis!
  "DESTRUCTIVE fresh start: delete every KI (type='ki') and the successor cache,
  then clear caches. Preserves `AGORA_USER` (accounts) and any `type='article'`
  rows. Take a mysqldump backup first. Returns rows removed."
  []
  (let [n (-> (node/q! db/ds ["DELETE FROM AGORA_NODE WHERE type = ?" ki-type])
              first
              :next.jdbc/update-count)]
    (node/q! db/ds ["DELETE FROM AGORA_SUCCESSOR"])
    (node/clear-caches!)
    n))
