(ns landing.endpoints.default-handler
  "Handler when route is not found, page not acceptable or method disallowed."
  (:require
   [clojure.java.io      :as io]
   [clojure.string       :as str]
   [reitit.ring          :as rring]
   [ring.middleware.gzip :as ring-gzip]))

(def ^:private default-lang "fr")

(defn- cookie-lang
  "Best-effort `lang` cookie read, mirroring landing.handler/cookie-lang."
  [req]
  (when-let [cookie (some (fn [[k v]] (when (= "cookie" (str/lower-case (name k))) v))
                          (:headers req))]
    (some-> (re-find #"(?i)\blang=(?:%3A|:)?(en|fr)\b" cookie)
            second
            str/lower-case)))

(defn- static-404-response
  "Return the static `/<lang>/404.html` page with a real 404 status."
  [req]
  (let [lang (or (cookie-lang req) default-lang)
        path (str "public/" lang "/404.html")]
    {:status 404
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (or (some-> (io/resource path)
                       slurp)
               "<!doctype html><title>404</title><h1>Not found</h1>")}))

(def default-handler
  (-> (rring/create-default-handler {:not-found static-404-response
                                     :not-acceptable static-404-response
                                     :method-not-allowed static-404-response})
      ring-gzip/wrap-gzip))
