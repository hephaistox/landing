(ns landing.agora.ki
  "Adapter for **KIs** — the `type = \"ki\"` rows in AGORA_NODE. Shapes the endpoint
  view and the KI write operations (create / edit / add-drop input / translate) on top
  of the shared node primitives (landing.agora.node); KI discovery/search/admin queries
  live here too. All graph decisions are in the domain; this ns only does KI-shaped I/O."
  (:require
   [landing.agora.db     :as db]
   [landing.agora.domain :as domain]
   [landing.agora.node   :as node]
   [landing.language     :as language]))

(def ^:private ki-type "ki")

;; ---------------------------------------------------------------------------
;; Read: assemble the endpoint-facing KI view
;; ---------------------------------------------------------------------------

(defn fetch-ki
  "The KI `id` as the endpoint view. Neighbours are light refs (inputs carry their
  TNLR + pinned id, successors only the id); the SPA fetches each by id for its card.
  Plus the version lineage and translations. nil if unknown."
  [id]
  (when-let [n (node/fetch-node id)]
    (let [tnlr (domain/tnlr-key n)]
      (-> n
          (assoc :output-statement (:statement n)
                 :inputs (domain/input-refs (:inputs n) (:pins n))
                 :successors (mapv (fn [sid] {:id sid}) (node/successors-of tnlr))
                 :versions (node/versions-of tnlr)
                 :translations (node/translations-of (:type n) (:name n) (:lang n)))
          (dissoc :statement :owner-id :pins)))))

(defn fetch-ki-by-major
  "The latest-minor KI of (name, major) in `lang` (with cross-language fallback), or
  nil. The permanent public identity behind /agora/{lang}/ki/{name}/{major}."
  [ki-name ki-major lang]
  (when-let [id (node/resolve-latest-id ki-type ki-name ki-major lang)] (fetch-ki id)))

;; ---------------------------------------------------------------------------
;; Write
;; ---------------------------------------------------------------------------

(defn create-ki
  "Create a brand-new KI (major 1, minor 0), no inputs yet. Returns it via fetch-ki."
  [owner-id {ki-name :name
             ki-title :title
             kind :kind
             ki-lang :lang
             statement :output-statement}]
  (let [id (node/uuid)
        lang (or ki-lang language/default-lang)]
    (node/insert-node! {:id id
                        :type ki-type
                        :name ki-name
                        :lang lang
                        :major 1
                        :minor 0}
                       {:kind kind
                        :title ki-title
                        :statement statement
                        :inputs []
                        :author (node/author-name owner-id)
                        :owner-id owner-id
                        :published-at (node/now-iso)})
    (node/evict-lineage! ki-type ki-name lang 1)
    (fetch-ki id)))

(defn- content-of
  "The immutable content map carried forward from a source node (its authored fields).
  `:source` (a provenance/citation map on seeded KIs) is carried so it survives edits."
  [n]
  (select-keys n [:kind :title :statement :author :owner-id :inputs :source]))

(defn- new-minor!
  "Insert a new minor of the concept `src` (same name/major/lang) with the given
  `content` (declared inputs carried in it), re-pin this concept's successors onto it,
  and return the new KI."
  [src content]
  (let [{:keys [name lang major]} src
        new-id (node/uuid)]
    (node/insert-node! {:id new-id
                        :type ki-type
                        :name name
                        :lang lang
                        :major major
                        :minor (node/next-minor ki-type name lang major)}
                       (assoc content :published-at (node/now-iso)))
    (node/repin-successors! ki-type name lang major new-id)
    (node/evict-lineage! ki-type name lang major)
    (fetch-ki new-id)))

(defn edit-ki
  "Edit KI `id` → a new minor version (statement/title/kind change; declared inputs
  carried forward)."
  [id
   owner-id
   {kind :kind
    ki-title :title
    statement :output-statement}]
  (when-let [src (node/fetch-node id)]
    (new-minor! src
                (assoc (content-of src)
                       :kind kind
                       :title ki-title
                       :statement statement
                       :author (node/author-name owner-id)
                       :owner-id owner-id))))

(defn add-input
  "Declare the KI referenced by `input` (name+major, in this KI's language) as an input
  of `id` → a new minor. Idempotent. Returns the updated (new-minor) KI."
  [id
   owner-id
   {in-name :name
    in-major :major}]
  (when-let [{:keys [lang]
              :as src}
             (node/fetch-node id)]
    (if (node/resolve-latest-id ki-type in-name in-major lang)
      (let [t {:type ki-type
               :name in-name
               :lang lang
               :major in-major}
            inputs (domain/add-declared (:inputs src) t)]
        ;; add-declared dedups, so re-adding an existing input never grows the
        ;; count — only a genuinely new input past the cap is rejected.
        (if (> (count inputs) domain/max-inputs)
          :input-limit
          (new-minor! src
                      (assoc (content-of src)
                             :inputs inputs
                             :author (node/author-name owner-id)
                             :owner-id owner-id))))
      (fetch-ki id))))

(defn drop-input
  "Remove the declared input `input` (name+major) from KI `id` → a new minor. Returns
  the updated (new-minor) KI."
  [id
   owner-id
   {in-name :name
    in-major :major}]
  (when-let [{:keys [lang]
              :as src}
             (node/fetch-node id)]
    (let [t {:type ki-type
             :name in-name
             :lang lang
             :major in-major}]
      (new-minor! src
                  (assoc (content-of src)
                         :inputs (domain/drop-declared (:inputs src) t)
                         :author (node/author-name owner-id)
                         :owner-id owner-id)))))

(defn translate-ki
  "Create a `to-lang` version of KI `id` and to-lang copies of its direct inputs
  (declared+pinned to those). Existing versions are untouched. Returns the to-lang KI."
  [id to-lang owner-id title statement]
  (when-let [{:keys [name major]
              :as src}
             (node/fetch-node id)]
    (when-not (node/lang-exists? ki-type name major to-lang)
      (let [author (node/author-name owner-id)
            declarations
            (mapv (fn [{in-name :name
                        in-major :major
                        in-lang :lang}]
                    (when-not (node/lang-exists? ki-type in-name in-major to-lang)
                      (when-let [s (node/fetch-node
                                    (node/resolve-latest-id ki-type in-name in-major in-lang))]
                        (node/insert-node! {:id (node/uuid)
                                            :type ki-type
                                            :name in-name
                                            :lang to-lang
                                            :major in-major
                                            :minor 0}
                                           {:kind (:kind s)
                                            :title (:title s)
                                            :statement (:statement s)
                                            :inputs []
                                            :author author
                                            :owner-id owner-id
                                            :published-at (node/now-iso)})
                        (node/evict-lineage! ki-type in-name to-lang in-major)))
                    {:type ki-type
                     :name in-name
                     :lang to-lang
                     :major in-major})
                  (:inputs src))]
        (node/insert-node! {:id (node/uuid)
                            :type ki-type
                            :name name
                            :lang to-lang
                            :major major
                            :minor 0}
                           {:kind (:kind src)
                            :title (if (nil? title) (:title src) title)
                            :statement (if (nil? statement) (:statement src) statement)
                            :inputs declarations
                            :author author
                            :owner-id owner-id
                            :published-at (node/now-iso)})
        (node/evict-lineage! ki-type name to-lang major)))
    (fetch-ki-by-major name major to-lang)))

;; ---------------------------------------------------------------------------
;; Discovery / search / admin
;; ---------------------------------------------------------------------------

(defn- card
  "A discovery/search card from a row with a `content` blob."
  [row]
  (let [c (node/decode-content (:content row))]
    (assoc (select-keys row [:id :type :name :lang :major :minor])
           :kind (:kind c)
           :title (:title c)
           :output-statement (:statement c))))

(defn search-kis
  "KIs matching `q` in name/title/statement, latest minor per lineage, scoped to `lang`.
  Blank `q` → []. Title/statement live in the content blob, so this is a LIKE scan."
  [q lang]
  (if (or (nil? q) (empty? q))
    []
    (let [like (str "%" q "%")]
      (mapv
       card
       (node/q!
        db/ds
        ["SELECT k.id, k.type, k.name, k.lang, k.major, k.minor, k.content FROM AGORA_NODE k
           WHERE k.type = 'ki' AND k.lang = ?
             AND (k.name LIKE ? OR k.content LIKE ?)
             AND k.minor = (SELECT MAX(k2.minor) FROM AGORA_NODE k2
                            WHERE k2.type = 'ki' AND k2.name = k.name
                              AND k2.major = k.major AND k2.lang = k.lang)
           ORDER BY k.name, k.major LIMIT 50"
         lang
         like
         like]
        node/kebab)))))

(defn list-kis
  "Up to 10 latest-minor KIs (with :output-statement for cards), sampled at random,
  scoped to `lang`."
  [lang]
  (mapv
   card
   (node/q!
    db/ds
    ["SELECT k.id, k.type, k.name, k.lang, k.major, k.minor, k.content FROM AGORA_NODE k
       WHERE k.type = 'ki' AND k.lang = ?
         AND k.minor = (SELECT MAX(k2.minor) FROM AGORA_NODE k2
                        WHERE k2.type = 'ki' AND k2.name = k.name
                          AND k2.major = k.major AND k2.lang = k.lang)
       ORDER BY RAND() LIMIT 10"
     lang]
    node/kebab)))

(defn list-tnrs
  "All KI lineages (name, major) with version/language/latest counts (admin page)."
  []
  (node/q!
   db/ds
   ["SELECT name, major, COUNT(*) AS versions, COUNT(DISTINCT lang) AS langs, MAX(minor) AS latest
      FROM AGORA_NODE WHERE type = 'ki' GROUP BY name, major ORDER BY name, major"]
   node/kebab))

(defn delete-tnr!
  "Drop an entire (name, major) lineage plus its successor-index rows. Returns the
  number of node rows deleted."
  [ki-name ki-major]
  (node/q!
   db/ds
   ["DELETE FROM AGORA_SUCCESSOR
      WHERE (input_name = ? AND input_major = ?)
         OR successor_id IN (SELECT id FROM AGORA_NODE WHERE name = ? AND major = ?)"
    ki-name
    ki-major
    ki-name
    ki-major])
  (let [n (:next.jdbc/update-count
           (node/q1! db/ds
                     ["DELETE FROM AGORA_NODE WHERE type = 'ki' AND name = ? AND major = ?"
                      ki-name
                      ki-major]))]
    (node/clear-caches!)
    n))

(defn compact-tnr!
  "Keep only the latest minor of each language of a (name, major) lineage; delete the
  rest. Returns the number of node rows deleted."
  [ki-name ki-major]
  (let
    [n
     (->>
       (node/q!
        db/ds
        ["SELECT lang, MAX(minor) AS latest FROM AGORA_NODE
           WHERE type = 'ki' AND name = ? AND major = ? GROUP BY lang"
         ki-name
         ki-major]
        node/kebab)
       (reduce
        (fn [acc {:keys [lang latest]}]
          (+
           acc
           (or
            (:next.jdbc/update-count
             (node/q1!
              db/ds
              ["DELETE FROM AGORA_NODE
                 WHERE type = 'ki' AND name = ? AND major = ? AND lang = ? AND minor < ?"
               ki-name
               ki-major
               lang
               latest]))
            0)))
        0))]
    (node/clear-caches!)
    n))

(defn sitemap-rows
  "Every public KI permalink as {:name :major :lang :lastmod}. published-at lives in the
  content blob, so the max per lineage is computed here."
  []
  (->> (node/q! db/ds
                ["SELECT name, major, lang, content FROM AGORA_NODE WHERE type = 'ki'"]
                node/kebab)
       (map (fn [r]
              (assoc (select-keys r [:name :major :lang])
                     :published-at
                     (:published-at (node/decode-content (:content r))))))
       (group-by (juxt :name :major :lang))
       (mapv (fn [[[name major lang] rows]]
               {:name name
                :major major
                :lang lang
                :lastmod (apply max-key str (map :published-at rows))}))))

(defn rebuild-successor-index!
  "Recompute AGORA_SUCCESSOR from every node's declared inputs (KIs and articles),
  dropping the in-memory caches. Delegates to the shared node layer; run daily."
  []
  (node/rebuild-successor-index!))
