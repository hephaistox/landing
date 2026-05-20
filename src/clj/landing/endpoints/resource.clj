(ns landing.endpoints.resource
  "Returns resources in directory `/`."
  (:require
   [clojure.string       :as str]
   [reitit.ring          :as rring]
   [ring.middleware.gzip :as ring-gzip]
   [ring.util.response   :as rr]))

(defn- cache-control
  "1 year for fingerprinted / static assets, short for HTML."
  [uri]
  (let [u (or uri "")]
    (cond
      (str/ends-with? u ".html") "public, max-age=300"
      (re-find #"\.(css|js|woff2?|ttf|png|jpg|jpeg|gif|svg|ico|webp)$" u)
      "public, max-age=31536000, immutable"
      :else "public, max-age=600")))

(defn resource-handler
  "Resources from directory `/` are returned, gzipped, with sensible Cache-Control."
  [request]
  (let [h (-> (rring/create-resource-handler {:path "/"})
              ring-gzip/wrap-gzip)]
    (when-let [response (h request)]
      (-> response
          (rr/header "Access-Control-Allow-Origin" "*")
          (rr/header "Cache-Control" (cache-control (:uri request)))))))
