(ns landing.agora.endpoints.lab
  "Hidden lab route for the Agora vertical slice (#47).

  Serves a static HTML shell that loads the `agora` build; the frontend mounts
  on `#agora-app`, reads the KI id from the URL (`/lab/ki/<id>`, or the seeded id
  by default) and renders the KI display component. Not linked from anywhere —
  reachable only by direct URL. Both `/lab/ki` and `/lab/ki/:id` serve the same
  shell. In :prod the shell (raw + gzipped) is cached after first read."
  (:require
   [clojure.java.io                   :as io]
   [landing.endpoints.cached-response :as cr]
   [landing.endpoints.html            :refer [html-middlewares]]))

(defn- build-shell
  []
  (cr/prepare {:status 200
               :headers {"Content-Type" "text/html; charset=utf-8"}
               :body (some-> (io/resource "public/agora/lab.html")
                             slurp)}))

(def ^:private prepared-shell (cr/cache-fn build-shell))

(defn lab-ki-response
  [req]
  (-> (prepared-shell)
      (cr/serve req)))

(defn lab-ki-route
  [prefix]
  [prefix {:get {:swagger {:tags #{:agora}}
                 :handler lab-ki-response
                 :middleware html-middlewares
                 :summary "Hidden lab page — KI display (id from URL, seeded by default)"}}])
