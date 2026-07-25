(ns landing.agora.db.source
  "New Wire. Reads bibliographic **works** — now `type=source` documents in AGORA_DOCUMENT, no longer
  a separate table. Resolves a citation's `content.:source` ref to the work's display fields, and
  searches/lists works for the authoring picker. The work's `content` carries the bibliographic
  fields; `:source-id` is the work document's id."
  (:require
   [clojure.edn          :as edn]
   [clojure.string       :as str]
   [landing.agora.db     :as db]
   [next.jdbc            :as jdbc]
   [next.jdbc.result-set :as rs]))

(def ^:private kebab {:builder-fn rs/as-unqualified-kebab-maps})

(defn- decode
  [s]
  (some-> s
          edn/read-string))

(defn- project
  "A work document (`id` + decoded `content`) → the resolved work `{:source-id :author-id
  :author-name :title :year :editor :url :owner-id}`."
  [id content]
  (when content
    (assoc (select-keys content [:author-id :author-name :title :year :editor :url :owner-id])
           :source-id
           id)))

(defn resolve-ref
  "Resolve a citation's `content.:source` ref `{:source-id :locator}` to the work's display fields +
  locator. `:source-id` is the work document's id. nil for a blank/absent ref or an unknown work."
  [{:keys [source-id locator]}]
  (when-not (str/blank? source-id)
    (when-let [row (jdbc/execute-one!
                    db/ds
                    ["SELECT content FROM AGORA_DOCUMENT WHERE id = ? AND type = 'source'"
                     source-id]
                    kebab)]
      (some-> (project source-id (decode (:content row)))
              (assoc :locator locator)))))

(defn- rows->works
  [rows]
  (into [] (keep (fn [{:keys [id content]}] (project id (decode content)))) rows))

(defn search
  "Works matching any subset of {:author :title :year} (ANDed); all blank → []. Matches are `LIKE`
  over the work's EDN `content` (crude — a picker aid, not precise search), published only, LIMIT 30."
  [{:keys [author title year]}]
  (let [clauses (cond-> []
                  (not (str/blank? title)) (conj ["content LIKE ?" (str "%" (str/trim title) "%")])
                  (not (str/blank? author)) (conj ["content LIKE ?"
                                                   (str "%" (str/trim author) "%")])
                  (some? year) (conj ["content LIKE ?" (str "%:year " year "%")]))]
    (if (empty? clauses)
      []
      (rows->works
       (jdbc/execute!
        db/ds
        (into
         [(str
           "SELECT id, content FROM AGORA_DOCUMENT
                       WHERE type = 'source' AND draft = 0 AND "
           (str/join " AND " (map first clauses))
           " LIMIT 30")]
         (map second clauses))
        kebab)))))

(defn list-recent
  "The caller's works (for one-click reuse). Published `type=source` documents owned by `owner-id`,
  LIMIT 10. (Ordering is not by recency — AGORA_DOCUMENT has no created_at column; a picker aid.)"
  [owner-id]
  (rows->works
   (jdbc/execute!
    db/ds
    ["SELECT id, content FROM AGORA_DOCUMENT
        WHERE type = 'source' AND draft = 0 AND content LIKE ? LIMIT 10"
     (str "%:owner-id \"" owner-id "\"%")]
    kebab)))
