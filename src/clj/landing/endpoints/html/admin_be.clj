(ns landing.endpoints.html.admin-be
  "Backend endpoint for the administration SPA. Serves a static HTML shell that
  loads `app-admin.js`; the SPA mounts itself on `#admin-panel`. In :prod, the
  shell (raw + gzipped) is cached after first read."
  (:require
   [clojure.java.io                   :as io]
   [landing.endpoints.cached-response :as cr]
   [landing.endpoints.html            :refer [html-middlewares]]))

(def ^:private admin-headers
  {"Content-Type" "text/html; charset=utf-8"
   "Access-Control-Allow-Methods" "GET, PUT, POST, DELETE"
   "Access-Control-Allow-Origin" "*"})

(defn- build-shell
  []
  (cr/prepare {:status 200
               :headers admin-headers
               :body (some-> (io/resource "public/all-kind-of-checks.html")
                             slurp)}))

(def ^:private prepared-shell (cr/cache-fn build-shell))

(defn admin-response-wo-body
  [_]
  {:status 200
   :headers admin-headers})

(defn admin-response
  [req]
  (-> (prepared-shell)
      (cr/serve req)))

(defn admin-route
  [prefix]
  [prefix {:get {:swagger {:tags #{:html}}
                 :handler admin-response
                 :middleware html-middlewares
                 :summary "Administration page"}
           :head {:swagger {:tags #{:html}}
                  :handler admin-response-wo-body
                  :middleware html-middlewares
                  :summary "Administration page"}}])
