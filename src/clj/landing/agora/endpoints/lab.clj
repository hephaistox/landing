(ns landing.agora.endpoints.lab
  "Hidden lab route for the Agora vertical slice (#47).

  Serves a static HTML shell that loads the `agora` build; the frontend mounts on
  `#agora-app` and, from the URL, renders either a KI (`/lab/ki[/<id>]`) or an
  article (`/lab/article/<id>`). Not linked from anywhere — reachable only by
  direct URL; every lab path serves the same shell. In :prod the shell (raw +
  gzipped) is cached after first read."
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

(defn lab-shell-response
  [req]
  (-> (prepared-shell)
      (cr/serve req)))

(defn lab-shell-route
  "Serve the Agora lab shell at `prefix`. The frontend decides what to render from
  the URL, so the same shell backs every lab path (KI and article)."
  [prefix]
  ;; :conflicting — the language segment `/agora/:lang/lab/...` overlaps the
  ;; literal API paths in the detector's eyes; reitit's matcher still prefers the
  ;; literal `api` segment, so routing is correct.
  [prefix {:conflicting true
           :get {:swagger {:tags #{:agora}}
                 :handler lab-shell-response
                 :middleware html-middlewares
                 :summary "Hidden lab page — Agora KI/article display (resource from URL)"}}])

(defn- build-public-shell
  []
  (cr/prepare {:status 200
               :headers {"Content-Type" "text/html; charset=utf-8"}
               :body (some-> (io/resource "public/agora/ki.html")
                             slurp)}))

(def ^:private prepared-public-shell (cr/cache-fn build-public-shell))

(defn public-ki-response
  [req]
  (-> (prepared-public-shell)
      (cr/serve req)))

(defn public-shell-route
  "Serve the public (indexable) shell at `prefix` — backs the permanent KI page
  (/ki/:name/:major) and the discoverability page (/discover). The frontend
  decides what to render from the URL."
  [prefix]
  ;; :conflicting — see lab-shell-route; the `/agora/:lang/…` language segment
  ;; overlaps literal API paths for the detector, but the matcher resolves it.
  [prefix {:conflicting true
           :get {:swagger {:tags #{:agora}}
                 :handler public-ki-response
                 :middleware html-middlewares
                 :summary "Public Agora page (KI permalink or discoverability)"}}])
