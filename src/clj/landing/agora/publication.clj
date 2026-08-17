(ns landing.agora.publication
  "Publications — the work-package that gathers a user's drafts and publishes them together. A
  publication is a `type=publication` document lineage in AGORA_DOCUMENT with the same lifecycle as
  any document: identity is its stable `name` (a cid), it is versioned by minor, and a rename is a
  new minor. It carries no epistemic kind; its `content` is `{:title :status :author :owner-id
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
   [landing.agora.cache       :as cache]
   [landing.agora.db.document :as db-doc])
  (:import (java.util UUID)))

(def ^:private lang-na
  "Placeholder for the NOT-NULL `lang` identity column: a publication has no content language, so its
  lang is `:zz` (ISO 639 private range) — never a real code, never read for resolution or display."
  :zz)

(defn- view
  "Endpoint view of a publication: its stable cid as `:id` (references survive a rename), the
  authored fields, and the owner exposed as the byline `:attributed-author`/`:attributed-author-id`
  (the single attribution name used across every document view)."
  [{:keys [name title status author owner-id published-at]}]
  {:id name
   :type :publication
   :title title
   :status status
   :attributed-author author
   :attributed-author-id owner-id
   :published-at published-at})

(defn- count-owned
  "How many publications `owner-id` has (any status) — used to auto-name a new one."
  [owner-id]
  (count (filter #(= owner-id (:owner-id %)) (db-doc/latest-any-of-type :publication))))

(defn create!
  "Open a new publication owned by `owner-id` (display name `author`), titled `title`. A **blank
  `title` is auto-named** `publication<N>` (N = the owner's publication count + 1) — Word-style, so a
  document can auto-provision one with no user interaction; it stays editable (rename). Mints a cid,
  status `:open`, `draft` while open, created outside any publication (no content language,
  `lang-na`). Reusable. Returns the view."
  [owner-id author title]
  (let [cid (db-doc/gen-cid)
        now (db-doc/now-iso)
        title (if (str/blank? title) (str "publication" (inc (count-owned owner-id))) title)
        content {:title title
                 :status :open
                 :author author
                 :owner-id owner-id
                 :published-at now}]
    (db-doc/insert! {:id (str (UUID/randomUUID))
                     :type :publication
                     :name cid
                     :lang lang-na
                     :major 1
                     :minor 0
                     :draft true
                     :content content
                     :computed {:pins []}
                     :published-at now
                     :publication-id nil})
    (view (assoc content :name cid))))

(defn- load-view
  "Cache loader: publication `cid` → its view (latest minor, drafts included), or nil."
  [cid]
  (some-> (db-doc/fetch-latest-any :publication cid)
          view))

(def ^:private view-cache
  "cid → publication view. A publication is resolved on every document read (its provenance link), so
  this fronts those lookups; `rename!`/`publish!`/`delete!` evict the mutated cid."
  (cache/loading 10000 load-view))

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
             (when-let [pub (db-doc/fetch-latest-any :publication cid)]
               (= owner-id (:owner-id pub))))))

(defn closed?
  "True when publication `cid` is closed (published). A closed publication has no private drafts — its
  gathered documents are all published, hence public — so its contents may be shown to anyone, unlike
  an open publication's staging drafts."
  [cid]
  (= :closed (:status (fetch cid))))

(defn- next-content
  "The publication's next-version content: carry title/status/author/owner-id from `pub`, stamp `now`,
  apply `overrides`. Draft state is derived from its `:status` (`:open` ⇒ draft) by `version-row`."
  [pub overrides now]
  (merge (select-keys pub [:title :status :author :owner-id]) {:published-at now} overrides))

(defn rename!
  "Rename publication `cid` to `title` (a new minor) on behalf of `owner-id`. Returns the view, or
  nil when the publication is unknown or not owned by `owner-id`."
  [owner-id cid title]
  (when-let [pub (db-doc/fetch-latest-any :publication cid)]
    (when (= owner-id (:owner-id pub))
      (let [now (db-doc/now-iso)
            content (next-content pub {:title title} now)]
        (db-doc/insert-next-minor! (db-doc/version-row pub
                                                       {:content content
                                                        :draft (= :open (:status content))
                                                        :publication-id nil
                                                        :published-at now}))
        (cache/evict! view-cache cid)
        (view (assoc content :name cid))))))

(defn publish!
  "Publish publication `cid` on behalf of `owner-id`: flip every draft it gathers to published and
  close the publication (a new minor, status `:closed`), atomically. Returns the closed view, or nil
  when the publication is unknown, not owned by `owner-id`, or already closed."
  [owner-id cid]
  (when-let [pub (db-doc/fetch-latest-any :publication cid)]
    (when (and (= owner-id (:owner-id pub)) (= :open (:status pub)))
      (let [now (db-doc/now-iso)
            content (next-content pub {:status :closed} now)]
        (db-doc/publish-publication! cid
                                     (db-doc/version-row pub
                                                         {:content content
                                                          :draft (= :open (:status content))
                                                          :publication-id nil
                                                          :published-at now}))
        (cache/evict! view-cache cid)
        (view (assoc content :name cid))))))

(defn delete!
  "Delete open publication `cid` and the drafts it gathers, on behalf of `owner-id`. Returns `true`
  on success, or nil when the publication is unknown, not owned by `owner-id`, or already closed."
  [owner-id cid]
  (when-let [pub (db-doc/fetch-latest-any :publication cid)]
    (when (and (= owner-id (:owner-id pub)) (= :open (:status pub)))
      (db-doc/delete-publication! cid)
      (cache/evict! view-cache cid)
      true)))

(defn list-visible
  "Publications for the index, newest first, optionally filtered by `q` (title substring). `scope`
  picks the set: `:mine` — those owned by `viewer`; `:all` — every publication. Both statuses show
  (open drafts and closed, published ones), each view carrying its `:author`/`:author-id` so the
  index can mark the viewer's own and link to the others'."
  [viewer scope q]
  (let [q (some-> q
                  str/trim
                  str/lower-case)]
    (into []
          (comp (filter (fn [p]
                          (and (or (= scope :all) (= viewer (:owner-id p)))
                               (or (str/blank? q)
                                   (str/includes? (str/lower-case (str (:title p))) q)))))
                (map view))
          (db-doc/latest-any-of-type :publication))))
