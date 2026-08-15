(ns landing.agora.document.write
  "The document write path (text-only for now): create a document, or edit one. Every create and edit
  happens **inside an open publication** — the result is a **draft** (minor NULL, mutable) tagged with
  that publication (`publication_id`). A draft is **edited in place** (same id); editing a published
  version starts a new draft; editing another owner's document forks. There is no publish-a-document-
  directly path — a draft becomes published only when its publication is published, which assigns each
  draft its lineage's next minor and clears the draft flag. Inputs/citations (predecessors/successors)
  are not handled yet — a version carries an empty `:inputs`."
  (:require
   [landing.agora.db.document :as db-doc])
  (:import (java.util UUID)))

(defn create!
  "Create a new document (text-only), major 1. `type` is `:ki`/`:article`; `fields` is
  `{:kind :title :text :lang}`. **`:type`, `:kind` and `:lang` are keywords** (`:ki`, `:inference`,
  `:fr`) — the caller coerces at the JSON boundary; the domain works in keywords. Owned by `owner-id`
  (display name `author`). The document is a **draft** (minor NULL until publish) gathered by
  `publication-id` (required — every create happens inside an open publication). Returns the new
  version's row id."
  [type owner-id author {:keys [kind title text lang]} publication-id]
  (let [now (db-doc/now-iso)]
    (db-doc/insert!
     {:id (str (UUID/randomUUID))
      :type type
      :name (db-doc/gen-cid)
      :lang (or lang :fr)
      :major 1
      :minor nil
      :draft true
      :content {:kind kind
                :title title
                :text text
                :author author
                :owner-id owner-id
                :inputs []
                :published-at now}
      :computed {:pins []}
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
  Carries the content fields (`:kind`, `:inputs`, `:source`, `:references`), takes the new
  `:title`/`:text`. Returns the draft's id (stable across in-place edits)."
  [id editor-id editor-name {:keys [title text]} publication-id]
  (when-let [doc (db-doc/fetch-id id)]
    (let [owner? (= editor-id (:owner-id doc))
          now (db-doc/now-iso)
          [owner-id author] (if owner? [(:owner-id doc) (:author doc)] [editor-id editor-name])
          content (-> (select-keys doc [:kind :inputs :source :references])
                      (assoc :title (or title (:title doc))
                             :text text
                             :author author
                             :owner-id owner-id
                             :published-at now))
          row (db-doc/version-row doc
                                  {:content content
                                   :draft true
                                   :publication-id publication-id
                                   :published-at now})]
      (if owner?
        (if-let [did
                 (db-doc/draft-of (:type doc) (:name doc) (:lang doc) (:major doc) publication-id)]
          (db-doc/update-draft! {:id did
                                 :content content
                                 :computed {:pins []}
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
