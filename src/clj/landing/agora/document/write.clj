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
   [landing.agora.db.document       :as db-doc]
   [landing.agora.document.identity :as di])
  (:import (java.util UUID)))

(defn- inputs+pins
  "Derive a draft's `content.:inputs` and publication-aware `computed.:pins` from its `text`. Inputs
  are the `[[…]]` citations in the text (`cite-refs`), each with `:lang` defaulted to `doc-lang` when
  the token omits it. Each is then resolved to an `:id` (`pin-all`): **the publication's own draft** of
  the cited lineage if it has one, else the **latest published** minor — so a draft cites the exact
  in-progress predecessor while inside a publication, and the published corpus otherwise."
  [text doc-lang publication-id]
  (let [inputs (mapv (fn [r] (update r :lang #(or % doc-lang))) (di/cite-refs text))]
    {:inputs inputs
     :pins (di/pin-all inputs #(db-doc/latest-of % publication-id))}))

(defn create!
  "Create a new document (text-only), major 1. `type` is `:ki`/`:article`; `fields` is
  `{:kind :title :text :lang}` plus, for a `work`, its bibliographic `:author-id`/`:year`/`:editor`/
  `:url`, and for an `extract` its `:locator`. **`:type`, `:kind` and `:lang` are keywords** (`:ki`,
  `:inference`, `:fr`) — the caller coerces at the JSON boundary; the domain works in keywords. Owned
  by `owner-id`; `author` is the byline name (the contributor for most documents, the cited author for
  a work, whose `:author-id` links it). The document is a **draft** (minor NULL until publish)
  gathered by `publication-id` (required — every create happens inside an open publication). Returns
  the new version's row id."
  [type
   owner-id
   author
   {:keys [kind title text lang author-id locator year editor url]}
   publication-id]
  (let [now (db-doc/now-iso)
        lang (or lang :fr)
        {:keys [inputs pins]} (inputs+pins text lang publication-id)]
    (db-doc/insert!
     {:id (str (UUID/randomUUID))
      :type type
      :name (db-doc/gen-cid)
      :lang lang
      :major 1
      :minor nil
      :draft true
      :content (cond-> {:kind kind
                        :title title
                        :text text
                        :author author
                        :owner-id owner-id
                        :inputs inputs
                        :published-at now}
                 author-id (assoc :author-id author-id)
                 locator (assoc :locator locator)
                 year (assoc :year year)
                 editor (assoc :editor editor)
                 url (assoc :url url))
      :computed {:pins pins}
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
  takes the new `:title`/`:text`, and **re-derives** the inputs/pins from the new text. Returns the
  draft's id (stable across in-place edits)."
  [id
   editor-id
   editor-name
   {:keys [title text kind author-id locator year editor url]}
   publication-id]
  (when-let [doc (db-doc/fetch-id id)]
    (let [owner? (= editor-id (:owner-id doc))
          now (db-doc/now-iso)
          [owner-id author] (if owner? [(:owner-id doc) (:author doc)] [editor-id editor-name])
          {:keys [inputs pins]} (inputs+pins text (:lang doc) publication-id)
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
                             :author author
                             :owner-id owner-id
                             :inputs inputs
                             :published-at now))
          row (assoc-in (db-doc/version-row doc
                                            {:content content
                                             :draft true
                                             :publication-id publication-id
                                             :published-at now})
               [:computed :pins]
               pins)]
      (if owner?
        (if-let [did
                 (db-doc/draft-of (:type doc) (:name doc) (:lang doc) (:major doc) publication-id)]
          (db-doc/update-draft! {:id did
                                 :content content
                                 :computed {:pins pins}
                                 :published-at now})
          (db-doc/insert! row))
        (db-doc/insert-next-major! row)))))

(defn delete!
  "Delete document `id` — a draft the caller owns, gathered by a publication. Removes that lineage's
  draft versions in the publication. Returns the publication cid on success (so the caller can
  refresh it), or nil when `id` is unknown, published, or owned by someone else."
  [editor-id id]
  (when-let [doc (db-doc/fetch-id id)]
    (when (and (:draft doc) (:publication-id doc) (= editor-id (:owner-id doc)))
      (db-doc/delete-draft! (:type doc) (:name doc) (:lang doc) (:publication-id doc))
      (:publication-id doc))))
