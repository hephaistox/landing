(ns landing.endpoints.resource
  "Returns resources in directory `/`. In :prod, responses (including their
  gzipped form for gzippable content types) are cached in memory on first hit
  so repeat requests skip both disk I/O and compression."
  (:require
   [clojure.string                     :as str]
   [landing.endpoints.cached-response  :as cr]
   [reitit.ring                        :as rring]
   [ring.util.response                 :as rr]))

(defn- cache-control
  "1 year for fingerprinted / static assets, short for HTML."
  [uri]
  (let [u (or uri "")]
    (cond
      (str/ends-with? u ".html") "public, max-age=300"
      (re-find #"\.(css|js|woff2?|ttf|png|jpg|jpeg|gif|svg|ico|webp)$" u)
      "public, max-age=31536000, immutable"
      :else "public, max-age=600")))

(def ^:private base-handler (rring/create-resource-handler {:path "/"}))

(def ^:private prepared-for-uri
  "URI → prepared response (with :body and optional :gzipped bytes)."
  (cr/cache-fn (fn [request] (cr/prepare (base-handler request)))
               (fn [request] (:uri request))))

(defn resource-handler
  "Resources from directory `/` with sensible Cache-Control. In :prod, bodies
  (and their gzipped variants) are cached in memory after first read."
  [request]
  (when-let [prepared (prepared-for-uri request)]
    (-> prepared
        (cr/serve request)
        (rr/header "Access-Control-Allow-Origin" "*")
        (rr/header "Cache-Control" (cache-control (:uri request))))))
