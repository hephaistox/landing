(ns landing.agora.name-cid-migration
  "One-off migration: rewrite every document's readable `name` into an opaque **cid**
  (issue #67). New documents already get a cid at creation; this converts the *existing*
  corpus — the seeded/legacy readable names — to the same opaque-key model, so identity is
  fully decoupled from the (now purely decorative) URL title-slug.

  A concept is a `(type, name)` pair — its language siblings and versions all share the
  name — so each pair gets **one** cid, applied to every one of its rows. Because a `name`
  is also the *reference key* every citation/input/pin points at, renaming a **ki** concept
  means rewriting, everywhere:
    - in-text `[[ki:<name>@<major>]]` citation tokens (in every document body),
    - `content.:inputs` TNLR `:name`s,
    - `computed.:pins` keys (`[type name lang major]`).
  `AGORA_SUCCESSOR` is a *derived cache*, so it is not rewritten in place — it is fully
  regenerated from the now-rewritten inputs via `document/rebuild-successor-index!`.

  **The 7 `type-<kind>` definition KIs are deliberately excluded** (kept readable): the
  kind→definition link (`document-domain/kind-def`) references them by that stable slug, so
  renaming them would break the badge/seed convention for no gain. They are already valid
  opaque-enough keys.

  Lives in `env/seed` (`:env-seed` alias); dev points at the shared **production** MySQL, so
  run it from a REPL, never a build. Always `plan` first (dry run), then `migrate!`:
    clj -M:env-dev:env-seed   ; or `bb repl`
    (require 'landing.agora.name-cid-migration :reload)
    (landing.agora.name-cid-migration/plan)      ; report, writes nothing
    (landing.agora.name-cid-migration/migrate!)  ; apply"
  (:require
   [clojure.string                :as str]
   [landing.agora.db              :as db]
   [landing.agora.document        :as document]
   [landing.agora.document-domain :as domain]
   [landing.agora.document-store  :as store]
   [next.jdbc                     :as jdbc]
   [next.jdbc.result-set          :as rs]))

(def ^:private kebab {:builder-fn rs/as-unqualified-kebab-maps})

(def excluded-names
  "Readable `name`s left untouched — the `type-<kind>` definition KIs, referenced by
  `kind-def` by that slug."
  (set (map :name (vals domain/kind-def))))

(defn already-cid?
  "True when `nm` is already a 10-char base62 cid (a document created after the cid switch).
  Such names are left alone — re-cid'ing them is needless churn and would move an already
  stable permalink. This also makes the migration idempotent (a second run is a no-op)."
  [nm]
  (some? (re-matches #"[0-9A-Za-z]{10}" (or nm ""))))

(defn- migratable? [[_ nm]] (and (not (contains? excluded-names nm)) (not (already-cid? nm))))

(defn- all-rows []
  (jdbc/execute!
   db/ds
   ["SELECT id, type, name, lang, major, minor, content, computed FROM AGORA_DOCUMENT"]
   kebab))

(defn- concepts
  "Distinct migratable `[type name]` pairs (excluded + already-cid names dropped)."
  [rows]
  (->> rows
       (map (juxt :type :name))
       distinct
       (filter migratable?)))

(defn- fresh-cid
  "A cid not in `taken` (this batch) and not already a `name` in the DB."
  [taken]
  (loop []
    (let [c (document/gen-cid)]
      (if (or (contains? taken c) (store/cid-taken? c)) (recur) c))))

(defn- build-mapping
  "`{[type name] → cid}` for every migratable concept — one stable cid per pair."
  [rows]
  (loop [pairs (concepts rows), taken #{}, m {}]
    (if-let [p (first pairs)]
      (let [c (fresh-cid taken)]
        (recur (rest pairs) (conj taken c) (assoc m p c)))
      m)))

(defn- ki-map
  "`{old-name → cid}` restricted to **ki** concepts (the in-text/input/pin reference targets)."
  [mapping]
  (into {} (keep (fn [[[ty nm] cid]] (when (= "ki" ty) [nm cid]))) mapping))

(defn- source-map
  "`{old-name → cid}` restricted to **source** concepts — the target of a document's
  `content.:source` reference."
  [mapping]
  (into {} (keep (fn [[[ty nm] cid]] (when (= "source" ty) [nm cid]))) mapping))

;; --- pure rewriters (over a ki-only old-name→cid map) -----------------------
(defn- rewrite-text
  "Repoint `[[ki:<old>@…` citation tokens to the cid. The `@` delimiter makes each literal
  `[[ki:<old>@` boundary exact (no name is a prefix-collision of another under this anchor)."
  [km text]
  (reduce (fn [t [old cid]] (str/replace t (str "[[ki:" old "@") (str "[[ki:" cid "@")))
          (or text "")
          km))

(defn- rewrite-inputs [km inputs]
  (mapv (fn [inp]
          (if (and (= "ki" (:type inp)) (contains? km (:name inp)))
            (assoc inp :name (km (:name inp)))
            inp))
        (or inputs [])))

(defn- rewrite-pins
  "Pins are `{[type name lang major] → id}` — rewrite the name slot of ki-keyed entries."
  [km pins]
  (into {}
        (map (fn [[[ty nm lang mj :as k] id]]
               [(if (and (= "ki" ty) (contains? km nm)) [ty (km nm) lang mj] k) id]))
        (or pins {})))

(defn- rewrite-source
  "Repoint a document's `content.:source` reference `{:name <source-name> …}` when the cited
  source concept is being renamed. Leaves absent/unmapped refs untouched."
  [sm source]
  (if (and (map? source) (contains? sm (:name source)))
    (assoc source :name (sm (:name source)))
    source))

(defn- row-changes
  "New `[name content-edn computed-edn]` for a row under `mapping`/`km`/`sm`, or nil if the row
  (an excluded type-def, with nothing to repoint) is untouched."
  [mapping km sm row]
  (let [content (store/decode-content (:content row))
        pins (store/decode-pins (:computed row))
        new-name (mapping [(:type row) (:name row)])
        content' (-> content
                     (update :text #(rewrite-text km %))
                     (update :inputs #(rewrite-inputs km %))
                     (update :source #(rewrite-source sm %)))
        pins' (rewrite-pins km pins)]
    (when (or new-name (not= content' content) (not= pins' pins))
      [(or new-name (:name row))
       (store/encode-content content')
       (store/encode-pins pins')])))

(defn plan
  "Dry run: report the concept→cid mapping and how many rows/tokens change. Writes nothing."
  []
  (let [rows (all-rows)
        mapping (build-mapping rows)
        km (ki-map mapping)
        sm (source-map mapping)
        touched (keep #(when-let [c (row-changes mapping km sm %)] [% c]) rows)
        token-hits (reduce + (for [r rows]
                               (let [t (:text (store/decode-content (:content r)))]
                                 (count (filter #(str/includes? (or t "") (str "[[ki:" % "@"))
                                                (keys km))))))]
    (println "── name→cid migration plan ──")
    (println "documents total :" (count rows))
    (println "concepts to cid :" (count mapping) "(type-defs + already-cid names left as-is)")
    (println "rows changed    :" (count touched))
    (println "ki cited tokens :" token-hits)
    (println)
    (doseq [[[ty nm] cid] (sort-by (comp str first) mapping)]
      (println (format "  %-8s %-28s → %s" ty nm cid)))
    {:documents (count rows)
     :concepts (count mapping)
     :rows-changed (count touched)
     :mapping mapping}))

(defn migrate!
  "Apply the migration in one transaction: rewrite names + citations + inputs + pins on
  every row, regenerate the `AGORA_SUCCESSOR` cache, and clear read caches. Returns a
  summary (incl. any consistency issues that remain, which should be none)."
  []
  (let [rows (all-rows)
        mapping (build-mapping rows)
        km (ki-map mapping)
        sm (source-map mapping)
        updates (keep (fn [r] (when-let [c (row-changes mapping km sm r)] [(:id r) c])) rows)]
    (jdbc/with-transaction [tx db/ds]
      (doseq [[id [nm content computed]] updates]
        (jdbc/execute!
         tx
         ["UPDATE AGORA_DOCUMENT SET name = ?, content = ?, computed = ? WHERE id = ?"
          nm content computed id])))
    (store/clear-caches!)
    (document/rebuild-successor-index!)
    (let [issues (try (document/consistency-issues) (catch Throwable _ ::skipped))]
      (println "migrated" (count updates) "rows;" (count mapping) "concepts renamed.")
      (println "consistency issues after:" (if (= ::skipped issues) "n/a" (count issues)))
      {:rows-updated (count updates)
       :concepts (count mapping)
       :mapping mapping
       :issues issues})))
