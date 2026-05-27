(ns landing.endpoints.resource
  "Returns resources in directory `/`. In :prod, responses (including their
  gzipped form for gzippable content types) are cached in memory on first hit
  so repeat requests skip both disk I/O and compression."
  (:require
   [clojure.string                    :as str]
   [landing.endpoints.cached-response :as cr]
   [reitit.ring                       :as rring]
   [ring.middleware.not-modified      :refer [wrap-not-modified]]
   [ring.util.response                :as rr]))

(defn- cache-control
  "1 year for fingerprinted / static assets; HTML must revalidate every time.

  Assets are fingerprinted at deploy (`scripts/cache_bust.clj`), so a changed
  file always gets a new URL — safe to cache forever. HTML keeps a stable URL
  and points at those fingerprinted assets, so it must NOT be cached: a stale
  page would reference asset URLs that the latest build no longer ships (each
  build is rebuilt from scratch) and 404. `no-cache` lets the browser store the
  page but forces an `If-Modified-Since` revalidation before use; combined with
  `wrap-not-modified` that costs a tiny `304` when the page is unchanged."
  [uri]
  (let [u (or uri "")]
    (cond
      (str/ends-with? u ".html") "no-cache"
      (re-find #"\.(css|js|woff2?|ttf|png|jpg|jpeg|gif|svg|ico|webp)$" u)
      "public, max-age=31536000, immutable"
      :else "public, max-age=600")))

(def ^:private base-handler (rring/create-resource-handler {:path "/"}))

(def ^:private prepared-for-uri
  "URI → prepared response (with :body and optional :gzipped bytes)."
  (cr/cache-fn (fn [request] (cr/prepare (base-handler request))) (fn [request] (:uri request))))

(defn- serve-resource
  "Resources from directory `/` with sensible Cache-Control. In :prod, bodies
  (and their gzipped variants) are cached in memory after first read. Returns
  nil when no resource matches, so the surrounding route chain falls through."
  [request]
  (when-let [prepared (prepared-for-uri request)]
    (-> prepared
        (cr/serve request)
        (rr/header "Access-Control-Allow-Origin" "*")
        (rr/header "Cache-Control" (cache-control (:uri request))))))

(def resource-handler
  "`serve-resource` wrapped in `wrap-not-modified`: when the client sends
  `If-Modified-Since` and the resource's `Last-Modified` is no newer, it
  answers `304 Not Modified` (empty body) instead of resending the file. The
  base resource handler already emits `Last-Modified`; this makes the
  `no-cache` HTML revalidation cheap. A nil response (no match) passes through."
  (wrap-not-modified serve-resource))
