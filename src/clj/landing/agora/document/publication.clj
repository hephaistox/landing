(ns landing.agora.document.publication
  "Publications — the work-package that gathers a user's drafts and publishes them together. A
  publication is a `type=publication` document lineage in AGORA_DOCUMENT with the same lifecycle as
  any document: identity is its stable `name` (a cid), it is versioned by minor, and a rename is a
  new minor. It carries no epistemic kind; its `content` is `{:title :status
  :published-at}` and it is `draft` while its status is `:open`.

  A publication has **no content language** — it is a container, resolved by its cid alone, and the
  drafts it gathers may be in different languages. The `lang` identity column (NOT NULL, shared with
  real documents) is therefore filled with a neutral placeholder (`lang-na`), never a real language,
  so nothing reads it as \"this publication is French\".

  Opening one is the simplest write — no inputs, so no pins or successor edges, just one row — and is
  reusable: an endpoint opens one manually today, and other authoring contexts (start authoring, an
  objection) will open one automatically."
  (:require
   [clojure.string            :as str]
   [landing.agora.auth        :as auth]
   [landing.agora.cache       :as cache]
   [landing.agora.db          :as db]
   [landing.agora.db.document :as db-doc]
   [next.jdbc                 :as jdbc]
   [next.jdbc.result-set      :as rs])
  (:import (java.sql SQLException)
           (java.util UUID)))

;; --- the publication's own queries ------------------------------------------------------------
;; Written here rather than in `db.document`: they are about *this* concept, and a namespace that
;; owns a concept is where someone looks for what it can do. They share the document read shape
;; (`db-doc/select-doc` + `row->doc`) instead of restating it. `publish-drafts!` is the one that
;; stayed behind — it assigns minors and rebuilds edges, which is versioning, not publication.

(defn documents
  "The documents publication `cid` gathers, newest first, as full documents. Each lineage has one row
  per publication (its draft, edited in place; published in place on close), so this is a flat select
  — no minor-collapse needed."
  [cid]
  (mapv db-doc/row->doc
        (jdbc/execute!
         db/ds
         [(str db-doc/select-doc "WHERE publication_id = ? ORDER BY published_at DESC") cid]
         {:builder-fn rs/as-unqualified-kebab-maps})))

(defn latest-row
  "The latest minor of publication `cid`, drafts included, or nil — an open publication is a draft the
  whole time, so a published-only lookup would find nothing.

  Keyed on the cid alone, and that is the **whole** key here: a publication has no content language
  (its `lang` column holds a neutral placeholder) and no second major, so (type, name) identifies the
  lineage and `minor` orders it. The same query for a KI would be wrong — it would pick across
  languages and across majors, where a minor of one says nothing about a minor of another."
  [cid]
  (some-> (jdbc/execute-one!
           db/ds
           [(str db-doc/select-doc
                 "WHERE type = 'publication' AND name = ? ORDER BY minor DESC LIMIT 1")
            cid]
           {:builder-fn rs/as-unqualified-kebab-maps})
          db-doc/row->doc))

(defn- lock-latest
  "On `tx`, the latest minor of publication `cid`, **locked** for the rest of the transaction, or nil.
  One read serving three purposes: it yields the publication as it stands, gives the minor to follow,
  and holds the lineage — so what is decided next is decided on a state nothing can move underneath."
  [tx cid]
  (some-> (jdbc/execute-one!
           tx
           [(str db-doc/select-doc
                 "WHERE type = 'publication' AND name = ? ORDER BY minor DESC LIMIT 1 FOR UPDATE")
            cid]
           {:builder-fn rs/as-unqualified-kebab-maps})
          db-doc/row->doc))

(defn delete-rows!
  "Remove publication `cid` and the drafts it gathers, in one transaction: the drafts (`draft = 1`,
  this `publication_id`) then every minor of the publication lineage. Published documents keep their
  `publication_id` as provenance."
  [cid]
  (try (jdbc/with-transaction
        [tx db/ds]
        (jdbc/execute! tx ["DELETE FROM AGORA_DOCUMENT WHERE publication_id = ? AND draft = 1" cid])
        (jdbc/execute! tx
                       ["DELETE FROM AGORA_DOCUMENT WHERE type = 'publication' AND name = ?" cid]))
       (catch SQLException e (db-doc/db-error! e))))

(def ^:private lang-na
  "Placeholder for the NOT-NULL `lang` identity column: a publication has no content language, so its
  lang is `:zz` (ISO 639 private range) — never a real code, never read for resolution or display."
  :zz)

(defn- view
  "Endpoint view of a publication: its stable cid as `:name`, the authored fields, and the owner
  exposed as the byline `:attributed-author`/`:attributed-author-id` (the single attribution name used
  across every document view). `:author` is the derived name the document carries, never a stored
  authored field.

  The cid under `:name`, like every other document's — a publication carries a row `:id` too, and
  handing the cid out under that name made the two indistinguishable to a caller holding one of them."
  [{:keys [name title status author owner-id published-at]}]
  {:name name
   :type :publication
   :title title
   :status status
   :attributed-author author
   :attributed-author-id owner-id
   :published-at published-at})

(defn- count-owned
  "How many publications `owner-id` has (any status) — used to auto-name a new one."
  [owner-id]
  (count (filter #(= owner-id (:owner-id %))
                 (db-doc/latest-page {:type :publication
                                      :drafts? true}))))

(defn create!
  "Open a new publication owned by `owner-id`, titled `title`. A **blank `title` is auto-named**
  `publication<N>` (N = the owner's publication count + 1) — Word-style, so a document can
  auto-provision one with no user interaction; it stays editable (rename). Mints a cid, status
  `:open`, `draft` while open, created outside any publication (no content language, `lang-na`).
  Reusable. Returns the view."
  [owner-id title]
  (let [cid (db-doc/gen-cid)
        now (db-doc/now-iso)
        author (auth/display-name owner-id)
        title (if (str/blank? title) (str "publication" (inc (count-owned owner-id))) title)
        content {:title title
                 :status :open
                 :published-at now}]
    (db-doc/insert! {:owner-id owner-id
                     :id (str (UUID/randomUUID))
                     :type :publication
                     :name cid
                     :lang lang-na
                     :major 1
                     :minor 0
                     :draft true
                     :content content
                     :computed (db-doc/build-computed [] author)
                     :published-at now
                     :publication-id nil})
    (view (assoc content :name cid :author author :owner-id owner-id))))

(defn- load-view
  "Cache loader: publication `cid` → its view (latest minor, drafts included), or nil."
  [cid]
  (some-> (latest-row cid)
          view))

(cache/defcache
 ^:private view-cache
 "cid → publication view. A publication is resolved on every document read (its
  provenance link), so this fronts those lookups; `rename!`/`publish!`/`delete!` evict the mutated
  cid."
 10000
 load-view)

(defn fetch
  "The publication `cid` as a view (its latest minor, drafts included), cached. nil for an unknown
  cid."
  [cid]
  (when cid (cache/fetch view-cache cid)))

(defn owns?
  "True when `owner-id` owns publication `cid` — the gate for its private draft views (its documents /
  graph). False for an unknown cid or a nil/other owner."
  [owner-id cid]
  (boolean (when (and owner-id cid)
             (when-let [pub (latest-row cid)] (= owner-id (:owner-id pub))))))

(defn closed?
  "True when publication `cid` is closed (published). A closed publication has no private drafts — its
  gathered documents are all published, hence public — so its contents may be shown to anyone, unlike
  an open publication's staging drafts."
  [cid]
  (= :closed (:status (fetch cid))))

(defn- next-content
  "The publication's next-version content: carry title/status from `pub`, stamp `now`, apply
  `overrides`. Draft state is derived from its `:status` (`:open` ⇒ draft) by `version-row`. The
  owner is not here — it is a column, carried by `version-row`."
  [pub overrides now]
  (merge (select-keys pub [:title :status]) {:published-at now} overrides))

(defn rename!
  "Rename publication `cid` to `title` (a new minor) on behalf of `owner-id`. Returns the view, or
  nil when the publication is unknown or not owned by `owner-id`."
  [owner-id cid title]
  (when-let [pub (latest-row cid)]
    (when (= owner-id (:owner-id pub))
      (let [now (db-doc/now-iso)
            author (auth/display-name owner-id)
            content (next-content pub {:title title} now)]
        (db-doc/insert-next-minor! (db-doc/version-row pub
                                                       {:content content
                                                        :computed (db-doc/build-computed [] author)
                                                        :draft (= :open (:status content))
                                                        :publication-id nil
                                                        :published-at now}))
        (cache/evict! view-cache cid)
        (view (assoc content :name cid :author author :owner-id (:owner-id pub)))))))

(defn publish!
  "Publish publication `cid` on behalf of `owner-id`: flip every draft it gathers to published and
  close the publication (a new minor, status `:closed`), atomically. Returns the closed view, or nil
  when the publication is unknown, not owned by `owner-id`, or already closed.

  Owns the transaction the two writes share, so the whole operation reads, decides, builds and
  inserts in one place — the storage keeps the versioning primitive (`publish-drafts!`) and this
  keeps what closing a publication means."
  [owner-id cid]
  (let [author (auth/display-name owner-id)
        closed (try (jdbc/with-transaction
                     [tx db/ds]
                     ;; both checks are made on the publication **as the transaction locked it**: two clicks on
                     ;; Publish would otherwise both read `:open` and both close it, and a reattribution or a
                     ;; rename landing in between would be decided against a state already gone
                     (when-let [pub (lock-latest tx cid)]
                       (when (and (= owner-id (:owner-id pub)) (= :open (:status pub)))
                         (db-doc/publish-drafts! tx cid)
                         (let [now (db-doc/now-iso)
                               content (next-content pub {:status :closed} now)
                               row (assoc (db-doc/version-row pub
                                                              {:content content
                                                               :computed
                                                               (db-doc/build-computed [] author)
                                                               :draft false
                                                               :publication-id nil
                                                               :published-at now})
                                          :minor
                                          (inc (:minor pub)))]
                           (db-doc/insert! tx row)
                           row))))
                    (catch SQLException e (db-doc/db-error! e)))]
    (when closed
      (cache/evict! view-cache cid)
      (view (assoc (:content closed) :name cid :author author :owner-id (:owner-id closed))))))

(defn delete!
  "Delete open publication `cid` and the drafts it gathers, on behalf of `owner-id`. Returns `true`
  on success, or nil when the publication is unknown, not owned by `owner-id`, or already closed."
  [owner-id cid]
  (when-let [pub (latest-row cid)]
    (when (and (= owner-id (:owner-id pub)) (= :open (:status pub)))
      (delete-rows! cid)
      (cache/evict! view-cache cid)
      true)))

(defn- in-scope?
  "Whether publication `p` belongs to `scope` for `viewer`:
   - `:visible` — what `viewer` may see: their own, either status, plus every **closed** publication
     whoever owns it. An open publication is private staging (the drafts it gathers are unpublished),
     a closed one has published everything it gathers — the very rule `owns?`/`closed?` apply to a
     publication's documents, lifted to the listing. An anonymous `viewer` (nil) owns nothing, so it
     leaves them the closed ones.
   - `:mine`    — owned by `viewer`, either status.
   - `:all`     — every publication, including other owners' private staging; administrators only."
  [viewer scope p]
  (case scope
    :visible (or (= :closed (:status p)) (= viewer (:owner-id p)))
    :mine (= viewer (:owner-id p))
    true))

(defn list-visible
  "Publications for the index, newest first, optionally filtered by `q` (title substring), restricted
  to `scope` (see `in-scope?`). Each view carries its `:author`/`:author-id` so the index can mark the
  viewer's own and link to the others'."
  [viewer scope q]
  (let [q (some-> q
                  str/trim
                  str/lower-case)]
    (into []
          (comp (filter (fn [p]
                          (and (in-scope? viewer scope p)
                               (or (str/blank? q)
                                   (str/includes? (str/lower-case (str (:title p))) q)))))
                (map view))
          (db-doc/latest-page {:type :publication
                               :drafts? true}))))
