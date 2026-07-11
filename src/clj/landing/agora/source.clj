(ns landing.agora.source
  "Bibliographic sources — now first-class **documents** of `type = \"source\"` in
  AGORA_DOCUMENT, not a separate table. A source is the shared *work* (a book/article): its
  `content` is `{:kind \"source\" :title :author :owner-id :year :editor}`, and its **owner is
  the cited author** (an AGORA_USER person, usually a login-less `external` like \"Sun Tzŭ\"),
  so `document/create`/`edit`/versioning/translation all apply to it for free — and, later,
  objections can target a source like any other document.

  A citing document keeps a single reference to a source on *its* side:
  `content.:source = {:name <source-cid> :major :locator}` — the shared work plus a per-citation
  locator (page/entry/verse). It is resolved to display fields on read (`resolve-ref`), so the
  book is a shared entity and the locator belongs to the KI (see agora/CLAUDE.md).

  This ns only *reads/searches/resolves* sources — creation goes through the generic
  `document/create \"source\"` (from `endpoints.source`), keeping the dependency acyclic
  (source → store only; document → source)."
  (:require
   [clojure.string               :as str]
   [landing.agora.db             :as db]
   [landing.agora.document-store :as store]))

(def source-type "source")

(defn- latest-source-rows
  "Every source lineage's latest-minor row (any language)."
  []
  (store/q!
   db/ds
   ["SELECT k.id, k.name, k.lang, k.major, k.minor, k.content FROM AGORA_DOCUMENT k
      WHERE k.type = ?
        AND k.minor = (SELECT MAX(k2.minor) FROM AGORA_DOCUMENT k2
                       WHERE k2.type = k.type AND k2.name = k.name
                         AND k2.major = k.major AND k2.lang = k.lang)"
    source-type]
   store/kebab))

(defn present
  "Client/display shape of a source, from a decoded content map + its `name` (cid). `:id` is
  the **cid** — the stable citation ref the client sends back as `:source-id`."
  [name content]
  {:id name
   :name name
   :title (:title content)
   :year (:year content)
   :editor (:editor content)
   :author-name (:author content)
   :author-id (:owner-id content)
   :published-at (:published-at content)})

(defn- present-row [row] (present (:name row) (store/decode-content (:content row))))

(defn present-doc
  "Shape a fetched source document (a `document/create`/`fetch` view — content keys inlined,
  `:owner-id` already renamed to `:author-id`) into the client source object."
  [doc]
  {:id (:name doc)
   :name (:name doc)
   :title (:title doc)
   :year (:year doc)
   :editor (:editor doc)
   :author-name (:author doc)
   :author-id (:author-id doc)
   :published-at (:published-at doc)})

(defn resolve-ref
  "Resolve a citing document's `content.:source` ref `{:name :major :locator}` to the display
  fields (title/year/editor + author name/id) of the current version of that source, plus the
  per-citation `:locator`. `lang` is the reader's language (cross-language fallback). nil for a
  blank/absent ref or an unknown source (graceful — renders nothing)."
  [{:keys [name major locator]} lang]
  (when-not (str/blank? name)
    (when-let [id (store/resolve-latest-id source-type name (or major 1) lang)]
      (when-let [d (store/fetch-document id)]
        {:id name
         :name name
         :source-id name
         :locator locator
         :title (:title d)
         :year (:year d)
         :editor (:editor d)
         :author-name (:author d)
         :author-id (:owner-id d)}))))

(defn search
  "Sources matching any subset of {:author :title :year} (ANDed), case-insensitive substring
  on title/author, exact year; all blank → []. Latest minor per lineage, LIMIT 30. The source
  corpus is small, so it filters in memory rather than LIKE-ing the EDN content blob."
  [{:keys [author title year]}]
  (if (and (str/blank? title) (str/blank? author) (nil? year))
    []
    (let [has? (fn [hay needle]
                 (str/includes? (str/lower-case (or hay "")) (str/lower-case needle)))]
      (->> (latest-source-rows)
           (map present-row)
           (filter (fn [s]
                     (and (or (str/blank? title) (has? (:title s) title))
                          (or (str/blank? author) (has? (:author-name s) author))
                          (or (nil? year) (= year (:year s))))))
           (take 30)
           vec))))

(defn list-recent
  "The most-recently-created sources (one-click reuse), LIMIT 10."
  []
  (->> (latest-source-rows)
       (map present-row)
       (sort-by :published-at #(compare %2 %1))
       (take 10)
       vec))

(defn names-of-author
  "The cids of source works whose author (owner) is `author-id` — used to find the documents
  that *cite* this person (a cited figure's profile)."
  [author-id]
  (into #{}
        (comp (map present-row)
              (filter #(= author-id (:author-id %)))
              (map :name))
        (latest-source-rows)))
