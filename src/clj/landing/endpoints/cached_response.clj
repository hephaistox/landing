(ns landing.endpoints.cached-response
  "Shared building blocks for in-memory + pre-gzipped HTTP responses.

  A *prepared* response has its body realized to a byte array under `:body`
  and, when the content type is gzippable, a parallel `:gzipped` byte array.
  `serve` then picks the right variant for the current request and adds the
  `Content-Encoding` / `Vary` headers.

  Downstream gzip middleware (e.g. `ring.middleware.gzip/wrap-gzip`) is a
  no-op on responses that already carry `Content-Encoding`, so it is safe
  to leave the middleware in place — it'll only gzip responses that this
  layer didn't pre-gzip."
  (:require
   [clojure.java.io :as io]
   [clojure.string  :as str]
   [env])
  (:import (java.io ByteArrayOutputStream)
           (java.util.zip GZIPOutputStream)))

(def prod? (= :prod env/env))

(def ^:private byte-array-class (class (byte-array 0)))

(defn- ->bytes
  [body]
  (cond
    (nil? body) nil
    (instance? byte-array-class body) body
    (string? body) (.getBytes ^String body "UTF-8")
    (instance? java.io.InputStream body) (with-open [in body] (.readAllBytes in))
    (instance? java.io.File body) (with-open [in (io/input-stream body)] (.readAllBytes in))
    :else nil))

(def ^:private gzippable-types
  "Content-type bases worth gzipping. Already-compressed binary formats
  (PNG/JPG/WebP/WOFF2/…) are intentionally absent."
  #{"text/html" "text/css" "text/plain" "text/xml" "text/javascript" "application/json"
    "application/javascript" "application/xml" "application/rss+xml" "application/atom+xml"
    "application/xhtml+xml" "image/svg+xml"})

(defn- header-value
  [headers header-name]
  (some (fn [[k v]] (when (= header-name (str/lower-case (name k))) v)) headers))

(defn- gzippable?
  [content-type]
  (when content-type
    (let [base (-> content-type
                   (str/split #";")
                   first
                   str/trim
                   str/lower-case)]
      (contains? gzippable-types base))))

(defn- gzip-bytes
  ^bytes [^bytes data]
  (let [baos (ByteArrayOutputStream.)]
    (with-open [gzos (GZIPOutputStream. baos)] (.write gzos data))
    (.toByteArray baos)))

(defn prepare
  "Realize the response body to bytes; if the content type is gzippable,
  precompute the gzipped variant as well. Returns nil for nil responses."
  [resp]
  (when resp
    (when-let [raw (->bytes (:body resp))]
      (let [ct (header-value (:headers resp) "content-type")
            gz (when (gzippable? ct) (gzip-bytes raw))]
        (cond-> (assoc resp :body raw)
          gz (assoc :gzipped gz))))))

(defn- accepts-gzip?
  [request]
  (when-let [ae (header-value (:headers request) "accept-encoding")] (re-find #"(?i)\bgzip\b" ae)))

(defn serve
  "Pick the right body variant for `request`, strip the bookkeeping key, and
  set `Vary: Accept-Encoding` so intermediate caches behave."
  [resp request]
  (when resp
    (let [vary {"Vary" "Accept-Encoding"}]
      (if (and (:gzipped resp) (accepts-gzip? request))
        (-> resp
            (assoc :body (:gzipped resp))
            (update :headers merge vary {"Content-Encoding" "gzip"})
            (dissoc :gzipped))
        (-> resp
            (update :headers merge vary)
            (dissoc :gzipped))))))


(defmacro cache-body
  "Expand at *compile time* to the prod or dev caching body based on `prod?`.
  In `:prod`, returns an atom-backed cache keyed by `(key-fn args)`; in `:dev`,
  returns a passthrough that calls the builder every time so file edits are
  seen live. Picking the branch here means only one body is ever compiled."
  [build-fn key-fn]
  (if prod?
    `(let [cache# (atom {})]
       (fn [& args#]
         (let [k# (apply ~key-fn args#)]
           (or (get @cache# k#)
               (let [v# (apply ~build-fn args#)]
                 (when v# (swap! cache# assoc k# v#))
                 v#)))))
    `(fn [& args#] (apply ~build-fn args#))))

(defn cache-fn
  "Wraps a builder in a `swap!`-based in-memory cache, but only in `:prod`.
  In `:dev` the builder is called every time, so file edits are seen live.
  The builder must already return a prepared response.

  `key-fn` is applied to the builder's arguments to compute the cache key
  (defaults to the args vector itself). Pass a more selective `key-fn` when
  the builder takes a value (e.g. a Ring request) that contains volatile
  pieces irrelevant to identity."
  ([build-fn] (cache-fn build-fn vector))
  ([build-fn key-fn] (cache-body build-fn key-fn)))
