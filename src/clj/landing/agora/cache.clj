(ns landing.agora.cache
  "Tiny wrapper over Caffeine loading caches for the Agora read model.

  Everything held here is derived/rebuildable (a node document, the successor
  index, latest-minor resolutions, …), so any entry — or a whole cache — may be
  dropped at any time; a miss simply re-loads from the DB. Reads go through these so
  the read-heavy workload rarely touches MySQL."
  (:import (com.github.benmanes.caffeine.cache Caffeine CacheLoader LoadingCache)))

(defn loading
  "A size-bounded loading cache. `load-fn` maps a key to its value (nil = no such
  value; not cached). `max-size` caps the number of entries (Caffeine evicts the
  least-recently-used beyond it)."
  ^LoadingCache [max-size load-fn]
  (-> (Caffeine/newBuilder)
      (.maximumSize (long max-size))
      (.build (reify
               CacheLoader
                 (load [_ k] (load-fn k))))))

(defn fetch
  "Value for `k`, loading + caching on a miss. nil when the loader yields nil."
  [^LoadingCache cache k]
  (.get cache k))

(defn evict!
  "Drop the entry for `k` (it will reload on next fetch)."
  [^LoadingCache cache k]
  (.invalidate cache k))

(defn clear! "Drop every entry." [^LoadingCache cache] (.invalidateAll cache))
