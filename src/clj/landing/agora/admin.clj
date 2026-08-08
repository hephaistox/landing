(ns landing.agora.admin
  "Maintenance over the document store for the admin page: list every lineage, scan references for
  consistency (dangling / self / successor-cache drift), and drop or compact a lineage. These are
  read-and-repair operations behind the owner guard — the normal write path is elsewhere.

  `type`/`lang` are DB strings here (`\"ki\"`, `\"fr\"`); the consistency rule keywords `type` at the
  read boundary because the domain (and the stored `:inputs`) work in keyword types."
  (:require
   [auto-core.log                    :as core-log]
   [clojure.edn                      :as edn]
   [clojure.set                      :as set]
   [landing.agora.db                 :as db]
   [landing.agora.db.document        :as db-doc]
   [landing.agora.document.cached-db :as cached-db]
   [landing.agora.document.identity  :as di]
   [next.jdbc                        :as jdbc]
   [next.jdbc.result-set             :as rs]))

(def ^:private kebab {:builder-fn rs/as-unqualified-kebab-maps})
(defn- q [& args] (apply jdbc/execute! args))
(defn- q1 [& args] (apply jdbc/execute-one! args))
(defn- t->s
  [t]
  (some-> t
          name))
(defn- decode
  [s]
  (or (some-> s
              edn/read-string)
      {}))

;; --- list lineages ---------------------------------------------------------

(defn all-tnrs
  "Every lineage of every type, one row per language version: `{:type :name :lang :major :versions
  :latest :title :kind}` — the latest minor's `:title`/`:kind` decoded for the admin table."
  []
  (mapv
   (fn [{:keys [content]
         :as r}]
     (let [c (decode content)]
       (-> r
           (assoc :title (:title c) :kind (:kind c))
           (dissoc :content))))
   (q
    db/ds
    ["SELECT d.type, d.name, d.lang, d.major, d.content, g.versions, g.latest
               FROM AGORA_DOCUMENT d
               JOIN (SELECT type, name, lang, major, COUNT(*) AS versions, MAX(minor) AS latest
                       FROM AGORA_DOCUMENT
                      GROUP BY type, name, lang, major) g
                 ON d.type = g.type AND d.name = g.name AND d.lang = g.lang
                    AND d.major = g.major AND d.minor = g.latest
              ORDER BY d.type, d.name, d.major, d.lang"]
    kebab)))

;; --- consistency scan ------------------------------------------------------

(defn- ref-issues
  "Reference issues of `doc` given `existing` (a set of live `[type name major]` keys): `:broken`
  (a ref whose lineage is absent in any language) and `:self` (a ref to the doc's own lineage — a
  degenerate cycle). Refs are the declared `:inputs` unioned with the `[[ki:…]]` citations re-derived
  from the text (robust to drift). Each ref is `{:name :major :lang}`."
  [doc existing]
  (let [self [(:type doc) (:name doc) (:major doc)]
        ref-id (juxt :type :name :major)
        refs (distinct (concat (:inputs doc)
                               (map #(update % :lang (fn [l] (or l (:lang doc))))
                                    (di/cite-refs (:text doc)))))
        pick (fn [rs]
               (->> rs
                    (map #(select-keys % [:name :major :lang]))
                    distinct
                    vec))]
    {:broken (pick (remove #(existing (ref-id %)) refs))
     :self (pick (filter #(= (ref-id %) self) refs))}))

(defn- successor-drift
  "AGORA_SUCCESSOR rows whose successor document no longer exists (e.g. an old minor removed by
  compaction), grouped by the cited input lineage and resolved to its latest published version so
  the admin can deep-link. A `rebuild` heals them; this makes the drift visible."
  []
  (->>
    (q
     db/ds
     ["SELECT s.input_type AS type, s.input_name AS name, s.input_lang AS lang,
                   s.input_major AS major, s.successor_id AS successor_id
              FROM AGORA_SUCCESSOR s
         LEFT JOIN AGORA_DOCUMENT d ON d.id = s.successor_id
             WHERE d.id IS NULL"]
     kebab)
    (group-by (juxt :type :name :lang :major))
    (mapv
     (fn [[[t n l mj] rows]]
       (let
         [row
          (q1
           db/ds
           ["SELECT minor, content FROM AGORA_DOCUMENT
                                WHERE type = ? AND name = ? AND lang = ? AND major = ? AND draft = 0
                                ORDER BY minor DESC LIMIT 1"
            t
            n
            l
            mj]
           kebab)]
         (cond-> {:type t
                  :name n
                  :lang l
                  :major mj
                  :dangling-successors (mapv :successor-id rows)}
           row (assoc :minor (:minor row) :title (:title (decode (:content row))))))))))

(defn consistency-issues
  "Every version with a reference problem — dangling (`:broken`), self-reference (`:self`), or
  successor-cache drift (`:dangling-successors`). Two scans (all versions, all live lineages) feed
  the pure `ref-issues` rule; the successor-cache drift is appended. Each entry pins its `:id` so
  the admin can deep-link to the exact version."
  []
  (let [existing (into #{}
                       (map (juxt (comp keyword :type) :name :major))
                       (q db/ds ["SELECT DISTINCT type, name, major FROM AGORA_DOCUMENT"] kebab))
        docs (mapv (fn [row]
                     (merge
                      (update (select-keys row [:id :type :name :lang :major :minor]) :type keyword)
                      (decode (:content row))))
                   (q db/ds
                      ["SELECT id, type, name, lang, major, minor, content FROM AGORA_DOCUMENT"]
                      kebab))]
    (-> (into []
              (keep (fn [doc]
                      (let [{:keys [broken self]} (ref-issues doc existing)]
                        (when (or (seq broken) (seq self))
                          (assoc (select-keys doc [:id :type :name :lang :major :minor :title])
                                 :broken broken
                                 :self self)))))
              docs)
        (into (successor-drift)))))

;; --- derived-state maintenance --------------------------------------------

(defn discrepancies
  "Pure diff of a `derivation-snapshot` against what the immutable `content` implies. Returns
  `{:pin-fixes [{:id :computed}…]   ; versions whose computed.:pins ≠ pin-all(content.:inputs)
    :edge-inserts [tuple…]          ; expected reverse edges missing from AGORA_SUCCESSOR
    :edge-deletes [tuple…]}`        ; indexed edges no longer expected (stale)
  Writes nothing; the corpus is consistent when all three are empty. Expected edges come from each
  **latest-published** version's inputs only. Tuples are DB-string form, matching the snapshot."
  [{:keys [versions edges]}]
  (let [latest-pub (reduce (fn [m {:keys [id type name lang major minor draft]}]
                             (if draft
                               m
                               (let [k [type name lang major]
                                     cur (get m k)]
                                 (if (or (nil? cur) (> minor (:minor cur)))
                                   (assoc m
                                          k
                                          {:minor minor
                                           :id id})
                                   m))))
                           {}
                           versions)
        latest-of (fn [{:keys [type name lang major]}]
                    (:id (get latest-pub [type name lang major])))
        latest-ids (into #{} (map (comp :id val)) latest-pub)
        edge-tuple (fn [sid {:keys [type name lang major]}]
                     [(clojure.core/name type) name (clojure.core/name lang) major sid])
        pin-fixes (into []
                        (keep (fn [{:keys [id inputs computed]}]
                                (let [expected (di/pin-all inputs latest-of)]
                                  (when (not= expected (:pins computed))
                                    {:id id
                                     :computed (assoc computed :pins expected)}))))
                        versions)
        expected-edges (into #{}
                             (comp (filter #(contains? latest-ids (:id %)))
                                   (mapcat (fn [{:keys [id inputs]}]
                                             (map #(edge-tuple id (:tnlr %))
                                                  (di/successor-tuples id inputs)))))
                             versions)]
    {:pin-fixes pin-fixes
     :edge-inserts (vec (set/difference expected-edges edges))
     :edge-deletes (vec (set/difference edges expected-edges))}))

(defn reconcile!
  "Detect-and-repair the derived caches: snapshot the corpus, diff it against what the immutable
  `content` implies (`discrepancies`), and apply **only** the differences — re-pin drifted versions,
  insert missing successor edges, delete stale ones. A consistent corpus writes nothing (and does not
  even flush the cache). Logs every discrepancy found, then evicts the read caches if anything
  changed. Returns a report `{:versions :pins-fixed :edges-inserted :edges-deleted}`."
  []
  (let [snap (db-doc/derivation-snapshot)
        {:keys [pin-fixes edge-inserts edge-deletes]} (discrepancies snap)
        drift? (or (seq pin-fixes) (seq edge-inserts) (seq edge-deletes))]
    (if drift?
      (core-log/warn (str "Agora reconcile: discrepancies found — "
                          "pins " (mapv :id pin-fixes)
                          "; edges+ " (vec edge-inserts)
                          "; edges- " (vec edge-deletes)))
      (core-log/info
       (str "Agora reconcile: no discrepancies (" (count (:versions snap)) " version(s) scanned)")))
    (db-doc/apply-pin-fixes! pin-fixes)
    (db-doc/insert-successor-edges! edge-inserts)
    (db-doc/delete-successor-edges! edge-deletes)
    (when drift? (cached-db/clear!))
    {:versions (count (:versions snap))
     :pins-fixed (count pin-fixes)
     :edges-inserted (count edge-inserts)
     :edges-deleted (count edge-deletes)}))

;; --- destructive maintenance ----------------------------------------------

(defn delete-tnr!
  "Drop one language version of a lineage — (type, name, lang, major) — its successor-index rows
  first (edges before nodes), then clear the read caches. Returns rows removed."
  [type doc-name lang major]
  (q
   db/ds
   ["DELETE FROM AGORA_SUCCESSOR
        WHERE (input_type = ? AND input_name = ? AND input_lang = ? AND input_major = ?)
           OR successor_id IN (SELECT id FROM AGORA_DOCUMENT
                               WHERE type = ? AND name = ? AND lang = ? AND major = ?)"
    (t->s type)
    doc-name
    lang
    major
    (t->s type)
    doc-name
    lang
    major])
  (let [n (:next.jdbc/update-count
           (q1 db/ds
               ["DELETE FROM AGORA_DOCUMENT WHERE type = ? AND name = ? AND lang = ? AND major = ?"
                (t->s type)
                doc-name
                lang
                major]))]
    (cached-db/clear!)
    n))

(defn compact-tnr!
  "Keep only the latest minor of one language version — (type, name, lang, major) — deleting the
  rest (their successor-index rows first), then clear the read caches. Returns rows removed."
  [type doc-name lang major]
  (let
    [latest
     (:latest
      (q1
       db/ds
       ["SELECT MAX(minor) AS latest FROM AGORA_DOCUMENT
                        WHERE type = ? AND name = ? AND lang = ? AND major = ?"
        (t->s type)
        doc-name
        lang
        major]
       kebab))]
    (q
     db/ds
     ["DELETE FROM AGORA_SUCCESSOR
          WHERE successor_id IN (SELECT id FROM AGORA_DOCUMENT
                                 WHERE type = ? AND name = ? AND lang = ? AND major = ? AND minor < ?)"
      (t->s type)
      doc-name
      lang
      major
      latest])
    (let
      [n
       (:next.jdbc/update-count
        (q1
         db/ds
         ["DELETE FROM AGORA_DOCUMENT
                     WHERE type = ? AND name = ? AND lang = ? AND major = ? AND minor < ?"
          (t->s type)
          doc-name
          lang
          major
          latest]))]
      (cached-db/clear!)
      n)))
