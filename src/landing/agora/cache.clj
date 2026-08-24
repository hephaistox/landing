(ns landing.agora.cache
  "Tiny wrapper over Caffeine loading caches for the Agora read model.

  Everything held here is derived/rebuildable (a node document, the successor
  index, latest-minor resolutions, …), so any entry — or a whole cache — may be
  dropped at any time; a miss simply re-loads from the DB. Reads go through these so
  the read-heavy workload rarely touches MySQL."
  (:import (com.github.benmanes.caffeine.cache Caffeine CacheLoader LoadingCache)
           (java.time Duration)))

;; The **vars** holding this app's caches, so one call can drop them all (`clear-all!`).
;;
;; Vars, not the caches themselves: a Caffeine cache has no identity that survives a namespace
;; reload, so registering the object leaves the registry pointing at a dead cache while the live one
;; is unreachable — the very hole a global clear exists to close. A var keeps its identity, is
;; re-rooted onto the rebuilt cache, and re-registers as a no-op (hence a set).
;;
;; A caller with a corpus-wide change knows what it changed, never which namespaces hold a copy of
;; it. Registering at the point of declaration is what keeps a cache declared anywhere from being one
;; a global clear cannot reach.
(defonce ^:private caches (atom #{}))

(defn register!
  "Register `cache-var` for `clear-all!` and return it. Called by `defcache`."
  [cache-var]
  (swap! caches conj cache-var)
  cache-var)

(defn loading
  "A size-bounded loading cache. `load-fn` maps a key to its value (nil = no such
  value; not cached). `max-size` caps the number of entries (Caffeine evicts the
  least-recently-used beyond it). With `ttl-seconds`, an entry also expires that long
  after it is written, so a value that goes stale between writes self-refreshes.

  The low-level constructor. Declare caches with `defcache`, which also registers them — one built
  here directly is one `clear-all!` does not reach."
  (^LoadingCache [max-size load-fn] (loading max-size load-fn nil))
  (^LoadingCache [max-size load-fn ttl-seconds]
   (-> (Caffeine/newBuilder)
       (.maximumSize (long max-size))
       (cond->
         ttl-seconds (.expireAfterWrite (Duration/ofSeconds (long ttl-seconds))))
       (.build (reify
                CacheLoader
                  (load [_ k] (load-fn k)))))))

(defmacro defcache
  "Declare a cache and register it for `clear-all!`. Same arguments as `loading`, after the name and
  an optional docstring:

    (defcache ^:private by-id \"Document by id.\" 20000 db/fetch-id)

  Declaring and registering are one gesture, so a cache cannot be added without a global clear
  reaching it — the failure mode of a hand-maintained list is that it is right until someone adds
  the cache that matters."
  [nom & body]
  (let [[docstring args] (if (string? (first body)) [(first body) (rest body)] [nil body])]
    `(do ~(if docstring `(def ~nom ~docstring (loading ~@args)) `(def ~nom (loading ~@args)))
         (register! (var ~nom)))))

(defn fetch
  "Value for `k`, loading + caching on a miss. nil when the loader yields nil."
  [^LoadingCache cache k]
  (.get cache k))

(defn evict!
  "Drop the entry for `k` (it will reload on next fetch)."
  [^LoadingCache cache k]
  (.invalidate cache k))

(defn clear! "Drop every entry." [^LoadingCache cache] (.invalidateAll cache))

(defn clear-all!
  "Drop every entry of every cache declared with `defcache`. What a change the whole corpus derives
  from calls — a person's byline re-derived, the reconcile — since the set of caches holding a copy
  is not something the caller can enumerate."
  []
  (run! (comp clear! deref) @caches))
