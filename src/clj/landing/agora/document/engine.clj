(ns landing.agora.document.engine
  "Shapes a document into its endpoint view, from the `db` layer (`db.document`) and the domain
  (`identity`, `kind`). Type-agnostic: every type shapes the same."
  (:require
   [clojure.string                     :as str]
   [landing.agora.db.document          :as db-doc]
   [landing.agora.document.identity    :as di]
   [landing.agora.document.kind        :as dk]
   [landing.agora.document.publication :as publication]
   [landing.agora.document.storage     :as ds]))

;; `document-errors` and `cite-titles` (defined below, with the error helpers and the card) are used by
;; `expand-document` above them — forward-declared so the view can carry its `:errors` and the titles
;; its citations read as.
(declare document-errors)
(declare cite-titles)

(defn- work-view
  "The bibliographic work `work-doc` shaped for display, plus this citation's `locator`:
  `{:id :name :major :lang :title :author-name :author-id :year :editor :url :locator}`. The work's
  cited author is its own byline (`kind/attributed-author[-id]`)."
  [locator work-doc]
  (when work-doc
    {:id (:id work-doc)
     :name (:name work-doc)
     :major (:major work-doc)
     :lang (:lang work-doc)
     :title (:title work-doc)
     :author-name (dk/attributed-author work-doc)
     :author-id (dk/attributed-author-id work-doc)
     :year (:year work-doc)
     :editor (:editor work-doc)
     :url (:url work-doc)
     :locator locator}))

(defn- resolve-work
  "The work an `extract` draws from, for display: an extract cites nothing but that one work, so it is
  simply its single input — resolved through `doc-storage` (cached), shaped by `work-view`, carrying the
  extract's own `:locator`. nil for a non-extract or a missing work. Never the byline — an extract
  belongs to its writer; this is the underlying work shown alongside."
  [doc-storage doc]
  (when (= :extract (:kind doc))
    (work-view (:locator doc)
               (some->> (:pins doc)
                        first
                        :id
                        (ds/fetch-id doc-storage)))))

(defn- resolve-target
  "The single node an `:illustration` / `:counter-example` is about — the claim it exemplifies or
  refutes — resolved for its opening phrase: `{:id :type :name :lang :major :title}`. These kinds cite
  nothing but that one claim, so it is simply their single input. nil for other kinds or when absent."
  [doc-storage doc]
  (when (contains? #{:illustration :counter-example} (:kind doc))
    (when-let [d (some->> (:pins doc)
                          first
                          :id
                          (ds/fetch-id doc-storage))]
      (select-keys d [:id :type :name :lang :major :title]))))

(defn- resolve-publication
  "Resolve a document's `publication-id` (a cid) to `{:name :title :status}` — the work-package it
  belongs to, so the reader can open it. Reads through `publication/fetch` (cached). nil when there
  is none."
  [pub-cid]
  (some-> pub-cid
          publication/fetch
          (select-keys [:name :title :status])))

(defn- resolve-input
  "A pinned input ref shaped for display: its pin plus the pinned document's `:title`, so an edge is
  named by what it references and never by its opaque cid. An `extract` input also carries the work it
  draws from (`:work`), so a document citing an extract can show the underlying work."
  [doc-storage
   {:keys [id]
    :as pin}]
  (let [d (ds/fetch-id doc-storage id)]
    (cond-> (assoc pin :title (:title d))
      (= :extract (:kind d)) (assoc :work (resolve-work doc-storage d)))))

(defn- resolve-inputs
  "Each pinned input of `doc` resolved for display (`resolve-input`); a pin with no id (a dangling
  reference) is dropped."
  [doc-storage doc]
  (into [] (comp (filter :id) (map #(resolve-input doc-storage %))) (:pins doc)))

(defn- successor-refs
  "Each successor lineage of `ref`, as `{:id latest-published-minor}`. Resolved in SQL: one id per
  lineage whose latest published minor still declares `ref`. No fetch, no domain collapse."
  [ref]
  (mapv (fn [id] {:id id}) (db-doc/successor-latest-ids ref)))

(defn- publication-successor-refs
  "The drafts publication `pub-cid` gathers that pin `doc`'s lineage, as `{:id}` refs — the in-progress
  successors. Public successor edges (`AGORA_SUCCESSOR`) are published-only, so this overlay is how a
  draft graph shows successors while authoring, mirroring how inputs are publication-scoped."
  [pub-cid doc]
  (let [k (di/tnlr doc)]
    (into []
          (comp (filter (fn [d] (some #(= (di/tnlr %) k) (:pins d)))) (map (fn [d] {:id (:id d)})))
          (publication/documents pub-cid))))

(defn- expand-document
  "Shape `doc` into the endpoint view — the document plus its resolved environment: inputs,
  successors, and (for an extract) the underlying `:work`. When `pub-cid` is given (the caller's active
  publication), its drafts that cite `doc` are folded into `:successors`, so authoring shows the draft
  graph both ways.

  Drops `:pins` and the internal `:author`/`:owner-id`/`:author-id`, exposing the byline person as the
  derived `:attributed-author` (name) + `:attributed-author-id` (id) — via `kind/attributed-author
  [-id]`: a work's cited author, else the document's owner. Name and profile link always agree.

  `:translations` (the concept's language siblings) is included so the language switcher can offer
  the other languages."
  [doc-storage doc pub-cid]
  (-> doc
      (assoc :inputs (resolve-inputs doc-storage doc)
             :attributed-author (dk/attributed-author doc)
             :attributed-author-id (dk/attributed-author-id doc)
             :work (resolve-work doc-storage doc)
             :target (resolve-target doc-storage doc)
             :publication (resolve-publication (:publication-id doc))
             :successors (cond-> (successor-refs doc)
                           pub-cid (into (publication-successor-refs pub-cid doc)))
             :cite-titles (cite-titles doc-storage doc pub-cid)
             :errors (document-errors doc-storage doc pub-cid)
             :translations (db-doc/translations-of (:type doc) (:name doc)))
      (dissoc :author :owner-id :author-id :pins :publication-id)))

;; ********************************************************************************
;; Picking a document — both reads fetch a single document, then shape it. Same shape, different
;; selector: by exact id, or by the latest published minor of a lineage (DB-resolved). `pub-cid` (the
;; caller's active publication, optional) folds that publication's draft successors into the view.

(defn read-by-id
  "Document `id` as the endpoint view, or nil. One fetch, by exact id."
  ([doc-storage id] (read-by-id doc-storage id nil))
  ([doc-storage id pub-cid]
   (when-let [doc (ds/fetch-id doc-storage id)] (expand-document doc-storage doc pub-cid))))

(defn read-by-major
  "The latest published minor of `ref` (a TNLR) as the endpoint view, or nil when the lineage has no
  published minor in that language. One fetch, DB-resolved."
  ([doc-storage ref] (read-by-major doc-storage ref nil))
  ([doc-storage ref pub-cid]
   (when-let [doc (ds/fetch-latest-revision doc-storage ref)]
     (expand-document doc-storage doc pub-cid))))

(defn- cite-doc
  "The document a citation `ref` resolves to, publication-aware like every other reference: `pub-cid`'s
  own draft of the lineage when it has one, else the latest published minor (cached). nil when neither
  exists."
  [doc-storage pub-cid ref]
  (or (when pub-cid
        (some->> (db-doc/draft-of (:type ref) (:name ref) (:lang ref) (:major ref) pub-cid)
                 (ds/fetch-id doc-storage)))
      (ds/fetch-latest-revision doc-storage ref)))

(defn- cite-titles
  "Each document cited inline in `doc`'s text, as `{:name cid :title current-title}`. Since a name is
  an opaque cid, this is what lets every flat rendering of the prose — a card excerpt, a hover
  preview, a meta description — read the citation as its title (`identity/plain-text`). Skips a
  citation whose lineage does not resolve."
  [doc-storage doc pub-cid]
  (into []
        (keep (fn [ref]
                (let [ref (update ref :lang #(or % (:lang doc)))]
                  (when-let [d (cite-doc doc-storage pub-cid ref)]
                    {:name (:name ref)
                     :title (:title d)}))))
        (di/cite-refs (:text doc))))

;; --- document errors ---------------------------------------------------------------------------
;; A document must be error-free to be published. Errors are **derived** on read (never stored), so
;; they stay fresh as neighbours change, and re-derived by construction at each create/edit. Each is a
;; structured map — enough for a specific, clickable message, never a generic sentence.

(defn- stale-inputs
  "The inputs of `doc` whose pin no longer points at the lineage's current publication-aware
  resolution — the pinned version is behind (a newer minor exists, published or as this publication's
  own draft). Each is the input TNLR + the `:current` id it should re-pin to. Empty when up to date."
  [doc pub-cid]
  (into []
        (keep (fn [pin]
                (let [current (db-doc/latest-of pin pub-cid)]
                  (when (and current (not= (:id pin) current))
                    (assoc (di/tnlr pin) :current current)))))
        (:pins doc)))

(defn document-errors
  "The structured problems that make `doc` invalid, publication-aware via `pub-cid`. Empty when the
  document is sound. Each carries what a specific clickable message needs:
   - `:stale-ref` — an input pins a version behind its lineage's current one; `:ref` is the referenced
     identity + its title, `:current` the up-to-date id to link/re-pin to.
   - `:missing-inputs` — a reasoning kind (`kind-requires-inputs?`) that declares no predecessor;
     `:doc-kind` is the kind, so the reader is told which kind demands an input."
  [doc-storage doc pub-cid]
  (cond-> (mapv (fn [{:keys [current]
                      :as s}]
                  {:error :stale-ref
                   :ref (-> (select-keys s [:type :name :lang :major])
                            (assoc :title (:title (ds/fetch-id doc-storage current))))
                   :current current})
                (stale-inputs doc pub-cid))
    (and (dk/kind-requires-inputs? (:kind doc)) (empty? (:inputs doc))) (conj
                                                                         {:error :missing-inputs
                                                                          :doc-kind (:kind doc)})))

(defn- card
  "A browse card for `doc`: identity and kind, title, prose, the byline, the underlying `:work` (for
  an extract) or its own bibliographic fields (for a work), the titles of the KIs it cites
  (`:cite-titles`), and its `:errors` (publication-aware via `pub-cid`) — enough to render a preview
  with a readable excerpt and an error bell, without the full input/successor environment."
  [doc-storage doc pub-cid]
  (-> doc
      (select-keys [:id
                    :type
                    :name
                    :lang
                    :major
                    :minor
                    :draft
                    :kind
                    :title
                    :text
                    :published-at
                    :year
                    :editor
                    :url])
      (assoc :attributed-author (dk/attributed-author doc)
             :attributed-author-id (dk/attributed-author-id doc)
             :work (resolve-work doc-storage doc)
             :target (resolve-target doc-storage doc)
             :publication (resolve-publication (:publication-id doc))
             :cite-titles (cite-titles doc-storage doc pub-cid)
             :errors (document-errors doc-storage doc pub-cid))))

(defn- lineage-key
  "The lineage a document belongs to — its identity minus minor. The overlay key: a publication draft
  and the published version it supersedes share it."
  [d]
  [(:type d) (:name d) (:lang d) (:major d)])

(defn- matches?
  "True when `q` (case-insensitive) occurs in a document's name, title or text — the same reach as the
  published search, applied to a publication's drafts."
  [q d]
  (str/includes? (str/lower-case (str (:name d) " " (:title d) " " (:text d))) (str/lower-case q)))

(def ^:private visible-drafts-limit
  "Cap on the drafts a feed leads with. Generous — they are a person's own work in progress, and there
  is no page after the first for them."
  200)

(defn- draft-filter
  "The `db-doc/draft-versions` narrowing for `viewer` — the visibility rule turned into a query filter
  (agora/CLAUDE.md, \"Who sees what\"): every draft for the platform owner, their own for a member,
  none for an anonymous reader. `viewer` is `{:id :admin?}`, or nil.

  The one place the rule becomes a filter, so the database does the restricting and a feed never
  carries a row its reader may not have."
  [viewer type lang]
  (cond
    (:admin? viewer) {:type type
                      :lang lang
                      :limit visible-drafts-limit}
    (:id viewer) {:type type
                  :lang lang
                  :owner (:id viewer)
                  :limit visible-drafts-limit}
    :else nil))

(defn- visible-drafts
  "The drafts of `type`/`lang` that `viewer` may see, newest first."
  [viewer type lang]
  (if-let [f (draft-filter viewer type lang)]
    (db-doc/draft-versions f)
    []))

(defn list-cards
  "One page of browse cards for `type` in `lang`, newest first (`limit`/`offset`). The published page
  comes from `doc-storage` (cached, and public — one shared page, never keyed by reader); the drafts
  `viewer` may see lead the first page and replace the published version of any lineage they
  supersede.

  Which drafts those are is the visibility rule, not the publication scope: everything the reader
  made, wherever they made it. `pub-cid` still decides how each card *resolves* — its pins and errors
  — which is the other question entirely."
  [doc-storage type lang limit offset pub-cid viewer]
  (let [drafts (visible-drafts viewer type lang)
        overridden (set (map lineage-key drafts))
        published (remove #(overridden (lineage-key %))
                          (ds/documents doc-storage type lang limit offset))
        head (if (zero? offset) drafts [])]
    (mapv #(card doc-storage % pub-cid) (concat head published))))

(defn search-cards
  "Browse cards for documents of `type` in `lang` matching `q` (name or content). A blank `q` returns
  no results. Not cached — the query runs per keystroke against the DB. The drafts `viewer` may see
  and that match lead the results, replacing the published version of any lineage they supersede."
  [doc-storage type lang q pub-cid viewer]
  (if (str/blank? q)
    []
    (let [drafts (filterv #(matches? q %) (visible-drafts viewer type lang))
          overridden (set (map lineage-key drafts))
          published (remove #(overridden (lineage-key %))
                            (db-doc/latest-page {:type type
                                                 :lang lang
                                                 :q q
                                                 :limit 50}))]
      (mapv #(card doc-storage % pub-cid) (concat drafts published)))))

(defn publication-cards
  "Browse cards for the documents a publication gathers — newest first. Each card carries `:errors`
  (via `card`), so the publication view flags each document (re-editing a stale one re-pins it)."
  [doc-storage pub-cid]
  (mapv (fn [doc] (card doc-storage doc pub-cid)) (publication/documents pub-cid)))

(defn- graph-node
  "One node of the publication graph, shaped for the client's mini-card: identity + kind + title +
  draft flag + `:errors` (for the bell) + `:in-publication?` (a draft this publication gathers vs a
  1-hop neighbour). nil for a missing id."
  [doc-storage pub-cid draft-ids id]
  (when-let [doc (ds/fetch-id doc-storage id)]
    {:id id
     :type (:type doc)
     :name (:name doc)
     :lang (:lang doc)
     :major (:major doc)
     :minor (:minor doc)
     :kind (:kind doc)
     :title (:title doc)
     :draft (:draft doc)
     :errors (document-errors doc-storage doc pub-cid)
     :in-publication? (contains? draft-ids id)}))

(defn publication-subgraph
  "The 1-hop graph of publication `pub-cid` for the graph view: the documents it gathers plus their
  **direct** predecessors and successors, as `{:nodes [graph-node…] :edges [{:from id :to id}…]}`. Edge
  direction is the implication (predecessor → dependent). Publication-aware: a draft's predecessors
  resolve to this publication's own drafts where they exist, and its in-progress draft successors are
  folded in alongside the published ones."
  [doc-storage pub-cid]
  (let [drafts (publication/documents pub-cid)
        draft-ids (into #{} (map :id) drafts)
        {:keys [edges neighbours]}
        (reduce (fn [acc d]
                  (let [d-id (:id d)
                        preds (into #{} (keep :id) (:pins d))
                        succs (into #{}
                                    (map :id)
                                    (concat (successor-refs (di/tnlr d))
                                            (publication-successor-refs pub-cid d)))]
                    (-> acc
                        (update :edges into (map (fn [p] [p d-id])) preds)
                        (update :edges into (map (fn [s] [d-id s])) succs)
                        (update :neighbours into preds)
                        (update :neighbours into succs))))
                {:edges #{}
                 :neighbours #{}}
                drafts)]
    {:nodes
     (into [] (keep #(graph-node doc-storage pub-cid draft-ids %)) (into draft-ids neighbours))
     :edges (mapv (fn [[from to]]
                    {:from from
                     :to to})
                  edges)}))

(defn sitemap-rows
  "Every published lineage's permalink row for the sitemap: `{:type :name :major :lang :title
  :lastmod}`, projected from `doc-storage`'s latest-published set."
  [doc-storage]
  (mapv #(select-keys % [:type :name :major :lang :title :lastmod])
        (ds/latest-published doc-storage)))

(def ^:private author-docs-limit
  "Cap on how many documents an author page lists (newest first). A holding value until the page
  paginates."
  50)

(defn- author-refs
  "The published documents attributed to `author-id` — their lineage's latest published minor, newest
  first, capped at `author-docs-limit`.

  Asked of the database rather than filtered out of the corpus: `:owner` narrows to the versions
  pointing at this person either way, which is a superset, and `kind/attributed-author-id` settles
  which of the two pointers actually signs it. Reading every published lineage and keeping the
  matching ones made an author page cost the whole corpus to show fifty cards."
  [_doc-storage author-id]
  (into []
        (comp (filter (fn [d] (= author-id (dk/attributed-author-id d)))) (take author-docs-limit))
        (db-doc/latest-page {:owner author-id})))

(defn recent-author-ids
  "The people most recently attributed a document, newest first, at most `limit`.

  The database collapses the corpus to one row per distinct pair of pointers, newest first
  (`recent-attributions`); this applies the domain rule (`kind/attributed-author-id`: a work's credited
  author, else the owner) and dedupes what that collapses together. So the cost is the number of
  people, not the number of versions, and who counts as an author is still decided in one place.

  **Every version counts, drafts included.** A member whose work is all in progress writes here just
  as much as one who has published, and their name is already public — the people search and their
  author page both answer to it. Restricting this to published versions would only make the one list
  meant to say who writes here the one place that does not."
  [limit]
  (into []
        (comp (keep dk/attributed-author-id) (distinct) (take limit))
        (db-doc/recent-attributions)))

(defn author-documents
  "Permalink link items of the documents attributed to `author-id` — the server-rendered author hub's
  list."
  [doc-storage author-id]
  (mapv #(assoc (select-keys % [:type :name :lang :major :title]) :lastmod (:published-at %))
        (author-refs doc-storage author-id)))

(defn- draft-cards
  "Cards for the drafts attributed to `author-id`, newest first. Grouped by the same domain rule as
  everything else, so a work credited to someone shows on their page and not on its writer's."
  [doc-storage author-id]
  (into []
        (comp (filter (fn [d] (= author-id (dk/attributed-author-id d))))
              (take author-docs-limit)
              (map #(card doc-storage % nil)))
        (db-doc/draft-versions {:owner author-id})))

(defn author-cards
  "Full browse cards for the documents attributed to `author-id` — the interactive author page's grid.

  With `drafts?`, the person's unpublished work is listed too, newest first. The schema says a draft
  is reachable by its exact-version URL and on the author profile; this is that profile, and the
  caller decides who is looking — a draft is private staging, so only its own author (or the platform
  owner) ever passes true. Without it a member whose work is all in progress reads as having written
  nothing, which is exactly what their page should not say to them."
  ([doc-storage author-id] (author-cards doc-storage author-id false))
  ([doc-storage author-id drafts?]
   (into (if drafts? (draft-cards doc-storage author-id) [])
         ;; the query already returns whole documents, so each is shaped straight into a card — it
         ;; used to hand back identities and fetch every one of them again
         (map #(card doc-storage % nil))
         (author-refs doc-storage author-id))))

(defn- link-of
  "A document's permalink link item `{:type :name :lang :major :title}` — `:lang` as a string, the
  form a server-rendered link builds its URL from."
  [doc]
  (-> (select-keys doc [:type :name :lang :major :title])
      (update :lang name)))

(defn resolve-links
  "Resolve neighbour refs (`{:id …}`) to permalink link items `{:type :name :lang :major :title}`,
  fetching each through `doc-storage` (cached). Skips a ref whose document is absent. Used to give a
  server-rendered body real, crawlable links instead of bare ids."
  [doc-storage refs]
  (into []
        (keep (fn [{:keys [id]}]
                (some->> id
                         (ds/fetch-id doc-storage)
                         link-of)))
        refs))
