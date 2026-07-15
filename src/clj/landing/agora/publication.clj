(ns landing.agora.publication
  "Publications — the work-package that gathers a user's drafts and publishes them together. A
  publication is a `type=publication` document lineage in AGORA_DOCUMENT, with the **same
  lifecycle as a KI**: identity is its stable `name` (cid), it is versioned by minor, and every
  edit (e.g. a rename) is an immutable new minor via `document/edit`. It carries no epistemic
  kind; its `content` is `{:title :author :owner-id :status :published-at}`. It is `draft` for as
  long as its `status` is open (draft mirrors status); references to it use the cid, so they
  survive a rename. The versioning axes (major/minor/name/lang) are the document's — a publication
  just triggers fewer of them: today no trigger bumps a publication's major or creates a
  translation, so in practice it stays at major 1 in one language, but that is trigger *policy*,
  not a missing axis. This ns is a thin facade over `document`: create → `document/create`,
  rename → `document/edit`, resolve → the general document resolver."
  (:require
   [clojure.string                 :as str]
   [landing.agora.db               :as db]
   [landing.agora.document         :as document]
   [landing.agora.document.lineage :as lineage]
   [landing.agora.document.store   :as store]))

(defn- view
  "Endpoint view of a publication row: the stable **cid** as `:id` (a publication is a lineage
  like a KI — identity is its `name`/cid, not the per-version row id, so references survive a
  rename → new minor), the authored fields, and `:owner-id` renamed to the public `:author-id`
  (as for documents). nil for a row that is not a publication."
  [doc]
  (when (= "publication" (:type doc))
    {:id (:name doc)
     :type (:type doc)
     :title (:title doc)
     :status (:status doc)
     :author (:author doc)
     :author-id (:owner-id doc)
     :published-at (:published-at doc)}))

(defn resolve-latest
  "The row id of the current version of publication lineage `cid`, or nil. A publication is a
  document like any other — the versioning is the document's; a publication just triggers fewer of
  it. By current trigger *policy* it has a single major and is not translated (no trigger defines a
  publication major bump or a translation yet), so its cid names one lineage: we read that
  lineage's `major`/`lang` and resolve its latest minor through the general document resolver —
  drafts included (`resolve-latest-any-id`), since a publication is `draft` while open."
  [cid]
  (when-let
    [{:keys [major lang]}
     (store/q1!
      db/ds
      ["SELECT major, lang FROM AGORA_DOCUMENT
                WHERE type = 'publication' AND name = ? ORDER BY minor DESC LIMIT 1"
       cid]
      store/kebab)]
    (store/resolve-latest-any-id "publication" cid major lang)))

(defn fetch
  "The publication lineage `cid` (its latest minor) as a view, or nil."
  [cid]
  (some-> (resolve-latest cid)
          store/fetch-document
          view))

(defn- open!
  "Create a publication through the **document engine** (no publication-specific write path):
  `document/create` mints the cid (or uses the given `name`), major 1, minor 0, the draft flag
  (open ⇒ draft), author/owner/timestamp; `:status \"open\"` rides in content like a KI's `:kind`.
  Created outside any publication, so `publication-id` is nil. Returns the publication view."
  [owner-id name title lang]
  (let [created (document/create "publication"
                                 owner-id
                                 (cond-> {:title title
                                          :lang lang
                                          :status "open"}
                                   name (assoc :name name))
                                 nil)]
    (fetch (:name created))))

(defn create!
  "Open a new publication owned by `owner-id`, titled `title` (content language `lang`,
  defaulting). Its `name` is a random cid. Status starts `open`. Returns the view."
  [owner-id title lang]
  (open! owner-id nil title lang))

(defn find-by-name
  "The publication whose stable lineage `name` (cid) matches, or nil — for seeds that need a
  deterministic publication. Same as `fetch` (identity is the cid); kept for intent at call sites."
  [name]
  (fetch name))

(defn ensure-named!
  "Idempotent create keyed on a stable `name`: the existing publication with that name, else a
  new one (owner `owner-id`, `title`). Lets a seed own a publication that survives a reseed and
  tag its documents deterministically."
  [owner-id name title lang]
  (or (find-by-name name) (open! owner-id name title lang)))

;; each publication is a lineage — group its minors by `name` and resolve to the latest one,
;; newest activity first (`MAX(published_at)` = the last edit across its minors)
(def ^:private lineages-newest-first
  ["SELECT name FROM AGORA_DOCUMENT
     WHERE type = 'publication' GROUP BY name ORDER BY MAX(published_at) DESC"])

(defn list-mine
  "The caller's **open** publications, newest first. Scans publication lineages and filters by
  owner in-process — publications are few and each `fetch` is cached; a denormalized owner column
  can optimize this later if the count grows."
  [owner-id]
  (->> (store/q! db/ds lineages-newest-first store/kebab)
       (keep (comp fetch :name))
       (filter #(and (= owner-id (:author-id %)) (= "open" (:status %))))
       vec))

(defn search
  "Publications matching `q` by title (case-insensitive), the caller's **own first**. A blank
  `q` returns the caller's own publications. Scans all publication lineages and filters
  in-process — they are few and each `fetch` is cached."
  [q owner-id]
  (let [q' (str/lower-case (str/trim (or q "")))]
    (->> (store/q! db/ds lineages-newest-first store/kebab)
         (keep (comp fetch :name))
         (filter (fn [p]
                   (if (str/blank? q')
                     (= owner-id (:author-id p))
                     (str/includes? (str/lower-case (or (:title p) "")) q'))))
         ;; the caller's own first; recency (the SQL order) is preserved within each group by
         ;; the stable sort — so a blank query returns the caller's newest publications first
         (sort-by #(not= owner-id (:author-id %)))
         vec)))

(defn rename!
  "Rename publication `cid` to `title` — an ordinary edit → new minor (immutable, exactly like a
  KI edit): `document/edit` re-stamps the version and carries the status forward. Only the owner
  may rename. `publication-id` is nil (a publication is authored outside any publication). Returns
  the updated view, or nil when `cid` is not a publication or `owner-id` does not own it."
  [cid owner-id title]
  (when-let [id (resolve-latest cid)]
    (let [doc (store/fetch-document id)]
      (when (and (= "publication" (:type doc)) (= owner-id (:owner-id doc)))
        (document/edit id owner-id {:title title} nil)
        (fetch cid)))))

(defn- summary
  "A lightweight card of a modified document for a publication's panel."
  [doc]
  (select-keys doc [:id :type :name :lang :major :minor :draft :title :kind]))

(defn modified
  "The distinct lineages this publication created — each at its latest tagged minor. This is
  the publication's *modified set* (what a close=publish will promote, and the staging panel)."
  [publication-id]
  (->> (store/q! db/ds
                 ["SELECT id FROM AGORA_DOCUMENT WHERE publication_id = ?" publication-id]
                 store/kebab)
       (keep (comp store/fetch-document :id))
       (group-by (juxt :type :name :lang :major))
       vals
       (mapv (comp summary lineage/latest-with-drafts))))

(defn open-drafts
  "The still-draft members of this publication's modified set — its unpublished cluster."
  [publication-id]
  (filterv :draft (modified publication-id)))
