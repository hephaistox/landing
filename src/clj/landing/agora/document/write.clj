(ns landing.agora.document.write
  "The document write path (text-only for now): create a new document, or edit an existing one into a
  new version. Every create and edit happens **inside an open publication** — the new version is a
  `draft` tagged with that publication (`publication_id`); it becomes published only when the
  publication is published (which flips the draft flag on every draft it gathered, #106). There is no
  publish-a-document-directly path. Inputs/citations (predecessors/successors) are not handled yet —
  a version carries an empty `:inputs`."
  (:require
   [landing.agora.db.document :as db-doc])
  (:import (java.time Instant)
           (java.time.temporal ChronoUnit)
           (java.util UUID)))

(def ^:private cid-alphabet "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")

(defn- gen-cid
  "A random 10-char base62 cid — the opaque, stable lineage name, never derived from the title."
  []
  (apply str (repeatedly 10 #(rand-nth cid-alphabet))))

(defn- now-iso [] (str (.truncatedTo (Instant/now) ChronoUnit/SECONDS)))

(defn create!
  "Create a new document (text-only), major 1 / minor 0. `type` is `:ki`/`:article`; `fields` is
  `{:kind :title :text :lang}`. **`:type`, `:kind` and `:lang` are keywords** (`:ki`, `:inference`,
  `:fr`) — the caller coerces at the JSON boundary; the domain works in keywords. Owned by `owner-id`
  (display name `author`). The document is a **draft** gathered by `publication-id` (required — every
  create happens inside an open publication). Returns the new version's row id."
  [type owner-id author {:keys [kind title text lang]} publication-id]
  (let [now (now-iso)]
    (db-doc/insert!
     {:id (str (UUID/randomUUID))
      :type type
      :name (gen-cid)
      :lang (or lang :fr)
      :major 1
      :minor 0
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
  "Edit document `id` into a NEW version (text-only), or nil when `id` is unknown. Version rule:
   - the editor **owns** the document → a new **minor** (same lineage, owner kept);
   - otherwise → a **fork**: a new **major** (minor 0) owned by the editor.
  The version number is chosen atomically inside the insert transaction (`insert-next-minor!` /
  `insert-next-major!`), so concurrent edits can't collide on the same version. Carries the
  content fields (`:kind`, `:inputs`, `:source`, `:references`), takes the new `:title`/`:text`. The
  new version is a **draft** tagged with `publication-id` (required — an edit happens inside an open
  publication). Returns the new id."
  [id editor-id editor-name {:keys [title text]} publication-id]
  (when-let [doc (db-doc/fetch-id id)]
    (let [owner? (= editor-id (:owner-id doc))
          now (now-iso)
          [owner-id author] (if owner? [(:owner-id doc) (:author doc)] [editor-id editor-name])
          row {:id (str (UUID/randomUUID))
               :type (:type doc)
               :name (:name doc)
               :lang (:lang doc)
               :major (:major doc)
               :draft true
               :content (-> (select-keys doc [:kind :inputs :source :references])
                            (assoc :title (or title (:title doc))
                                   :text text
                                   :author author
                                   :owner-id owner-id
                                   :published-at now))
               :computed {:pins []}
               :published-at now
               :publication-id publication-id}]
      ;; owner → next minor of this major; other user → fork to the next major. The version number
      ;; is assigned atomically in the DB transaction.
      (if owner? (db-doc/insert-next-minor! row) (db-doc/insert-next-major! row)))))
