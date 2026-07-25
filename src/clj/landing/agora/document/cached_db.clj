(ns landing.agora.document.cached-db
  "New Wire. Read `db` through cache.

  A read misses to `db`, stores the row, then serves memory on a hit.

  Holds the immutable id → document row. A row is write-once, so an entry is never stale. The mutable
  indexes (versions, successors, translations) stay uncached — the engine reads them from `db`."
  (:require
   [landing.agora.cache             :as caffeine]
   [landing.agora.db.document       :as db]
   [landing.agora.document.identity :as di]
   [landing.agora.document.storage  :as ds]))

;; `by-id` is immutable — a row is write-once, so an id's document is never stale.
;; `by-tnlr` is volatile — it maps a lineage to its latest **published** id, which changes when a
;; new minor is published, so it must be evicted on `on-new-tnlr`. The document itself still comes
;; from the write-once `by-id`, so only the small tnlr→id mapping is ever invalidated.
(def ^:private by-id (caffeine/loading 20000 db/fetch-id))

(def ^:private by-tnlr (caffeine/loading 20000 db/latest-published-id))

(defn- evict! "Drop the cached document for `id`." [id] (caffeine/evict! by-id id))

(defn clear!
  "Drop every cached document and lineage-resolution."
  []
  (caffeine/clear! by-id)
  (caffeine/clear! by-tnlr))

(defrecord DocumentCachedDB []
  ds/DocumentStorage
    (fetch-id [_this id] (caffeine/fetch by-id id))
    ;; resolve the lineage's latest published id (cached), then its document (cached)
    (fetch-latest-revision [_this ref]
      (some->> (caffeine/fetch by-tnlr (di/tnlr ref))
               (caffeine/fetch by-id)))
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

