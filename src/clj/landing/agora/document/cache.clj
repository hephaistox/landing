(ns landing.agora.document.cache
  "New Wire. Read-through cache over `db`. A read misses to `db`, stores the row, then serves memory
  on a hit.

  Holds the immutable id → document row. A row is write-once, so an entry is never stale. The mutable
  indexes (versions, successors, translations) stay uncached — the engine reads them from `db`."
  (:require
   [landing.agora.cache       :as caffeine]
   [landing.agora.db.document :as db]))

(def ^:private by-id (caffeine/loading 20000 db/fetch))

(defn document
  "Document for `id`, cached. Loads via `db/fetch` on a miss. nil if absent."
  [id]
  (caffeine/fetch by-id id))

(defn evict! "Drop the cached document for `id`." [id] (caffeine/evict! by-id id))

(defn clear! "Drop every cached document." [] (caffeine/clear! by-id))
