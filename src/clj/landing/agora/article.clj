(ns landing.agora.article
  "Adapter for **articles** — the `type = \"article\"` rows in AGORA_NODE. Same
  immutable-content / mutable-computed / versioned model as KIs (landing.agora.ki),
  built on the shared node primitives (landing.agora.node).

  An article's immutable `content` is {:title :body :author :owner-id :published-at
  :inputs [TNLR…]}, where `:inputs` are the KIs it cites — parsed from the body's
  `[[ki:…]]` tokens (living, name+major references). Its mutable `computed` is
  {:pins {tnlr-key → id}} resolving each citation to the latest-minor KI id. Because
  citations reuse the KI input model, the reverse index (AGORA_SUCCESSOR) and the
  re-pin-on-write machinery cover articles for free: editing a cited KI re-points an
  article's pins, and 'which articles cite this KI' is a successor lookup filtered to
  type article. The API surfaces the resolved citations as `:cites`."
  (:require
   [landing.agora.db     :as db]
   [landing.agora.domain :as domain]
   [landing.agora.node   :as node]
   [landing.language     :as language]))

(def ^:private article-type "article")

;; ---------------------------------------------------------------------------
;; Read
;; ---------------------------------------------------------------------------

(defn fetch-article
  "The article `id` as the endpoint view: identity + title/body/published-at, its
  resolved `:cites` (each cited KI's TNLR + pinned id) and its version lineage. nil if
  `id` is unknown or is not an article."
  [id]
  (when-let [n (node/fetch-node id)]
    (when (= article-type (:type n))
      (-> n
          (assoc :cites (domain/input-refs (:inputs n) (:pins n))
                 :versions (node/versions-of (domain/tnlr-key n)))
          (dissoc :owner-id :pins :inputs)))))

(defn fetch-article-by-major
  "The latest-minor article of (name, major) in `lang` (cross-language fallback), or
  nil. The permanent public identity behind /agora/{lang}/article/{name}/{major}."
  [art-name art-major lang]
  (when-let [id (node/resolve-latest-id article-type art-name art-major lang)] (fetch-article id)))

;; ---------------------------------------------------------------------------
;; Write
;; ---------------------------------------------------------------------------

(defn- article-content
  "The immutable content for an article, with its cited KIs parsed from `body` as
  input declarations (living, name+major) in the article's `lang`."
  [owner-id title body lang]
  {:title title
   :body body
   :inputs (domain/cite-refs body lang)
   :author (node/author-name owner-id)
   :owner-id owner-id
   :published-at (node/now-iso)})

(defn create-article
  "Create a brand-new article (major 1, minor 0). Cited KIs are parsed from the body.
  Returns it via fetch-article."
  [owner-id {art-name :name
             title :title
             body :body
             art-lang :lang}]
  (let [id (node/uuid)
        lang (or art-lang language/default-lang)]
    (node/insert-node! {:id id
                        :type article-type
                        :name art-name
                        :lang lang
                        :major 1
                        :minor 0}
                       (article-content owner-id title body lang))
    (node/evict-lineage! article-type art-name lang 1)
    (fetch-article id)))

(defn edit-article
  "Edit article `id` → a new minor version (title/body change; cited KIs re-parsed from
  the new body). nil if `id` is unknown or is not an article."
  [id
   owner-id
   {title :title
    body :body}]
  (when-let [{:keys [name lang major]
              :as src}
             (node/fetch-node id)]
    (when (= article-type (:type src))
      (let [new-id (node/uuid)]
        (node/insert-node! {:id new-id
                            :type article-type
                            :name name
                            :lang lang
                            :major major
                            :minor (node/next-minor article-type name lang major)}
                           (article-content owner-id
                                            (if (nil? title) (:title src) title)
                                            (if (nil? body) (:body src) body)
                                            lang))
        (node/repin-successors! article-type name lang major new-id)
        (node/evict-lineage! article-type name lang major)
        (fetch-article new-id)))))

;; ---------------------------------------------------------------------------
;; Discovery
;; ---------------------------------------------------------------------------

(defn- card
  "A discovery card from a row with a `content` blob."
  [row]
  (let [c (node/decode-content (:content row))]
    (assoc (select-keys row [:id :type :name :lang :major :minor])
           :title (:title c)
           :published-at (:published-at c))))

(defn list-articles
  "Latest-minor articles scoped to `lang`, most recent first (for the discover page)."
  [lang]
  (->>
    (node/q!
     db/ds
     ["SELECT a.id, a.type, a.name, a.lang, a.major, a.minor, a.content FROM AGORA_NODE a
           WHERE a.type = 'article' AND a.lang = ?
             AND a.minor = (SELECT MAX(a2.minor) FROM AGORA_NODE a2
                            WHERE a2.type = 'article' AND a2.name = a.name
                              AND a2.major = a.major AND a2.lang = a.lang)"
      lang]
     node/kebab)
    (mapv card)
    (sort-by :published-at #(compare %2 %1))
    vec))
