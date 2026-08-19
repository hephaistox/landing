(ns landing.agora.document.write
  "The document write path (text-only for now): create a document, or edit one. Every create and edit
  happens **inside an open publication** — the result is a **draft** (minor NULL, mutable) tagged with
  that publication (`publication_id`). A draft is **edited in place** (same id); editing a published
  version starts a new draft; editing another owner's document forks. There is no publish-a-document-
  directly path — a draft becomes published only when its publication is published, which assigns each
  draft its lineage's next minor and clears the draft flag. A version's **inputs** are the `[[…]]`
  citations parsed from its text, resolved to publication-aware **pins** on write; the reverse
  successor edges are still derived by the reconcile, not on write."
  (:require
   [landing.agora.auth              :as auth]
   [landing.agora.db.document       :as db-doc]
   [landing.agora.document.identity :as di]
   [landing.agora.document.kind     :as dk])
  (:import (java.util UUID)))

(defn- byline
  "The byline name a version caches in its derived `computed`: the display name of the person its
  `content` attributes it to — a work's cited `:author-id`, else its `:owner-id`. Read from
  AGORA_USER at every write, so the name never comes from the client and never lands in the
  immutable content."
  [content]
  (auth/display-name (dk/attributed-author-id content)))

(defn- inputs+pins
  "Derive a draft's `content.:inputs` and publication-aware `computed.:pins`. Inputs are the `[[…]]`
  citations in the `text` (`cite-refs`), **plus** the structural `target` when the kind has one (an
  example/counter-example's referenced claim) — folded in so it is pinned and successor-indexed like
  any edge, though it lives out of the prose. Each is language-defaulted to `doc-lang`, then resolved to
  an `:id` (`pin-all`): the publication's own draft of the cited lineage if it has one, else the latest
  published minor."
  [text doc-lang publication-id target]
  (let [default-lang (fn [r] (update r :lang #(or % doc-lang)))
        text-inputs (mapv default-lang (di/cite-refs text))
        target (some-> target
                       (select-keys [:type :name :lang :major])
                       (update :type #(or % :ki))
                       default-lang)
        ;; cap the number of inputs (`max-inputs`): each pin is up to 2 DB round-trips on a tiny
        ;; connection pool, so an unbounded citation list is a write-amplification vector
        inputs (into []
                     (take di/max-inputs)
                     (distinct (cond-> text-inputs
                                 target (conj target))))]
    {:inputs inputs
     :pins (di/pin-all inputs #(db-doc/latest-of % publication-id))}))

(defn create!
  "Create a new document (text-only), major 1. `type` is `:ki`/`:article`; `fields` is
  `{:kind :title :text :lang}` plus, for a `work`, its bibliographic `:author-id`/`:year`/`:editor`/
  `:url`, and for an `extract` its `:locator`. **`:type`, `:kind` and `:lang` are keywords** (`:ki`,
  `:inference`, `:fr`) — the caller coerces at the JSON boundary; the domain works in keywords. Owned
  by `owner-id`; the byline **name** is derived from the person the content points at (`byline`), not
  supplied. The document is a **draft** (minor NULL until publish) gathered by `publication-id`
  (required — every create happens inside an open publication). Returns the new version's row id."
  [type
   owner-id
   {:keys [kind title text lang author-id locator year editor url target]}
   publication-id]
  (let [now (db-doc/now-iso)
        lang (or lang :fr)
        {:keys [inputs pins]} (inputs+pins text lang publication-id target)
        content (cond-> {:kind kind
                         :title title
                         :text text
                         :owner-id owner-id
                         :inputs inputs
                         :published-at now}
                  author-id (assoc :author-id author-id)
                  locator (assoc :locator locator)
                  year (assoc :year year)
                  editor (assoc :editor editor)
                  url (assoc :url url))]
    (db-doc/insert! {:id (str (UUID/randomUUID))
                     :type type
                     :name (db-doc/gen-cid)
                     :lang lang
                     :major 1
                     :minor nil
                     :draft true
                     :content content
                     :computed (db-doc/computed pins (byline content))
                     :published-at now
                     :publication-id publication-id})))

(defn edit!
  "Edit the lineage of document `id` inside `publication-id`, or nil when `id` is unknown. All results
  are a **draft** (minor NULL):
   - the editor **owns** the lineage → **this publication's draft** of it, edited **in place** (same
     id) when one exists, else a **new draft** started from `id`'s content (the future next minor,
     assigned on publish);
   - **someone else's** → a **fork**: a new **major**, owned by the editor.
  So re-editing the same lineage in one publication always lands on the one draft — never a duplicate.
  Carries `:kind` and the bibliographic/`:locator` extras forward (a new value in `fields` overrides),
  takes the new `:title`/`:text`, and **re-derives** the inputs/pins and the byline from the new
  content. Returns the draft's id (stable across in-place edits)."
  [id editor-id {:keys [title text kind author-id locator year editor url target]} publication-id]
  (when-let [doc (db-doc/fetch-id id)]
    (let [owner? (= editor-id (:owner-id doc))
          now (db-doc/now-iso)
          {:keys [inputs pins]} (inputs+pins text (:lang doc) publication-id target)
          content (-> (select-keys doc [:kind :author-id :locator :year :editor :url])
                      (merge (into {}
                                   (remove (comp nil? val))
                                   {:kind kind
                                    :author-id author-id
                                    :locator locator
                                    :year year
                                    :editor editor
                                    :url url}))
                      (assoc :title (or title (:title doc))
                             :text text
                             :owner-id (if owner? (:owner-id doc) editor-id)
                             :inputs inputs
                             :published-at now))
          computed (db-doc/computed pins (byline content))
          row (db-doc/version-row doc
                                  {:content content
                                   :computed computed
                                   :draft true
                                   :publication-id publication-id
                                   :published-at now})]
      (if owner?
        (if-let [did
                 (db-doc/draft-of (:type doc) (:name doc) (:lang doc) (:major doc) publication-id)]
          (db-doc/update-draft! {:id did
                                 :content content
                                 :computed computed
                                 :published-at now})
          (db-doc/insert! row))
        (db-doc/insert-next-major! row)))))

(defn delete!
  "Delete document `id` — a draft the caller owns, in `publication-id`. Removes that lineage's draft
  versions in the publication. `publication-id` must be the draft's own publication — a delete is
  scoped to the publication the caller is working in. Returns the publication cid on success (so the
  caller can refresh it), or nil when `id` is unknown, published, in another publication, or owned by
  someone else."
  [editor-id id publication-id]
  (when-let [doc (db-doc/fetch-id id)]
    (when (and (:draft doc) (= publication-id (:publication-id doc)) (= editor-id (:owner-id doc)))
      (db-doc/delete-draft! (:type doc) (:name doc) (:lang doc) (:publication-id doc))
      (:publication-id doc))))
