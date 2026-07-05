(ns landing.agora.endpoints.lab
  "Hidden lab route for the Agora vertical slice (#47).

  Serves a static HTML shell that loads the `agora` build; the frontend mounts on
  `#agora-app` and, from the URL, renders either a KI (`/lab/ki[/<id>]`) or an
  article (`/lab/article/<id>`). Not linked from anywhere — reachable only by
  direct URL; every lab path serves the same shell. In :prod the shell (raw +
  gzipped) is cached after first read."
  (:require
   [clojure.java.io                   :as io]
   [landing.agora.ki                  :as ki]
   [landing.agora.seo                 :as seo]
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

(def ^:private public-template
  "The raw public shell, read once; SEO metadata is injected per request."
  (delay (some-> (io/resource "public/agora/ki.html")
                 slurp)))

(defn- html-response
  [body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"
             "Cache-Control" "public, max-age=600"}
   :body body})

(defn public-shell-response
  "Serve the public shell for a non-KI page (discover / preferences) with generic
  OpenGraph metadata."
  [req]
  (let [lang (or (get-in req [:path-params :lang]) "fr")
        head (seo/generic-head
              (seo/base-url req)
              lang
              "/discover"
              "Agora — Knowledge Items"
              "Reasoning made legible — a knowledge graph of challengeable reasoning steps.")]
    (html-response (seo/inject @public-template head lang))))

(def ki-page-response
  "Serve the public KI permalink shell with per-KI SEO: title, description,
  OpenGraph and schema.org Article (name, description, datePublished, author),
  server-rendered so crawlers and unfurlers see it without running the SPA."
  (fn [req]
    (let [{:keys [lang name major]} (:path-params req)
          major-n (try (Integer/parseInt (str major)) (catch Exception _ 1))
          ki (ki/fetch-ki-by-major name major-n lang)
          base (seo/base-url req)
          head (if ki
                 (seo/ki-head base lang name major-n ki)
                 (seo/generic-head base lang (str "/ki/" name "/" major-n) "Agora" "Agora"))]
      (html-response (seo/inject @public-template head lang)))))

(defn public-shell-route
  "Serve the public shell for discover / preferences (generic OG metadata)."
  [prefix]
  ;; :conflicting — see lab-shell-route; the `/agora/:lang/…` language segment
  ;; overlaps literal API paths for the detector, but the matcher resolves it.
  [prefix {:conflicting true
           :get {:swagger {:tags #{:agora}}
                 :handler public-shell-response
                 :middleware html-middlewares
                 :summary "Public Agora page (discover / preferences)"}}])

(defn ki-page-route
  "Serve the public KI permalink shell with server-rendered SEO metadata."
  [prefix]
  [prefix {:conflicting true
           :get {:swagger {:tags #{:agora}}
                 :handler ki-page-response
                 :middleware html-middlewares
                 :summary "Public KI permalink (SEO head injected)"}}])

(def sitemap-response
  "sitemap.xml of all KI permalinks + discover pages, generated from the DB so it
  reflects every publication."
  (fn [req]
    {:status 200
     :headers {"Content-Type" "application/xml; charset=utf-8"
               "Cache-Control" "public, max-age=3600"}
     :body (seo/sitemap-xml (seo/base-url req) (ki/sitemap-rows))}))

(defn sitemap-route
  [prefix]
  [prefix {:conflicting true
           :get {:handler sitemap-response
                 :middleware html-middlewares
                 :no-doc true
                 :summary "Agora sitemap"}}])
