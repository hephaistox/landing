(ns landing.agora.document.cached-db
  "Read `db` through cache.

  A read misses to `db`, stores the row, then serves memory on a hit.

  Holds the id → document row. Its `content` is write-once; its derived fields (the byline `:author`,
  the `:pins`) change only when the reconcile repairs them, which clears this cache. The mutable
  indexes (versions, successors, translations) stay uncached — the engine reads them from `db`."
  (:require
   [landing.agora.cache             :as caffeine]
   [landing.agora.db.document       :as db]
   [landing.agora.document.identity :as di]
   [landing.agora.document.storage  :as ds]))

;; `by-id` holds a row whose content is write-once; the reconcile clears the whole cache when it
;; repairs a derived field, so an id's document is never stale.
;; `by-tnlr` is volatile — it maps a lineage to its latest **published** id, which changes when a
;; new minor is published, so it must be evicted on `on-new-tnlr`. The document itself still comes
;; from the write-once `by-id`, so only the small tnlr→id mapping is ever invalidated.
(def ^:private by-id (caffeine/loading 20000 db/fetch-id))

(def ^:private by-tnlr (caffeine/loading 20000 db/latest-published-id))

;; A browse page depends on the whole set of a type's published lineages, so it can't be
;; invalidated per document; it expires by TTL instead — a page may be a few minutes stale.
(def ^:private page-ttl-seconds 120)

(def ^:private by-page
  (caffeine/loading 2000
                    (fn [[type lang limit offset]] (db/published-of-type type lang limit offset))
                    page-ttl-seconds))

;; The sitemap and the author hubs both need the whole set of published-latest documents — a
;; corpus scan crawled infrequently. A single-entry TTL cache spares the DB a full scan per hit,
;; expiring on the same hour as those pages' Cache-Control; the engine projects/filters in memory.
(def ^:private latest-ttl-seconds 3600)

(def ^:private latest-set
  (caffeine/loading 1 (fn [_] (db/published-latest-docs)) latest-ttl-seconds))

(defn- evict! "Drop the cached document for `id`." [id] (caffeine/evict! by-id id))

(defn clear!
  "Drop every cached document, lineage-resolution and browse page."
  []
  (caffeine/clear! by-id)
  (caffeine/clear! by-tnlr)
  (caffeine/clear! by-page)
  (caffeine/clear! latest-set))

(defrecord DocumentCachedDB []
  ds/DocumentStorage
    ;; nil id (an unresolved/dangling pin) → no document; guard it, since a nil key would blow up the
    ;; underlying cache
    (fetch-id [_this id] (when id (caffeine/fetch by-id id)))
    ;; resolve the lineage's latest published id (cached), then its document (cached)
    (fetch-latest-revision [_this ref]
      (some->> (caffeine/fetch by-tnlr (di/tnlr ref))
               (caffeine/fetch by-id)))
    (documents [_this type lang limit offset] (caffeine/fetch by-page [type lang limit offset]))
    (published-latest [_this] (caffeine/fetch latest-set :all))
    ;; evict one cached row by id — a draft is edited in place (same id), so its write-once entry is
    ;; stale after the write
    (publish-change! [_this change-id] (evict! change-id))
    (probe-tnr-languages [_this _tnr] nil)
    ;; a new minor changes which id is latest for this lineage — drop the stale tnlr→id entry
    (on-new-tnlr [_this tnlr] (caffeine/evict! by-tnlr (di/tnlr tnlr))))

(def document-cached-db (DocumentCachedDB.))

(comment
  (ds/fetch-id document-cached-db "07a02357-b10d-4a91-a92d-d2269d95b62d")
  ;;
)

(comment
  (ds/fetch-latest-revision document-cached-db
                            {:type :article
                             :name "hzkr69fHJl"
                             :lang :fr
                             :major 1})
  ;;
)

