(ns landing.agora.endpoints.shell
  "Serves the Agora SPA shell (`resources/public/agora/ki.html`, which loads the
  `agora` build and mounts `#agora-app`). The frontend decides what to render from
  the URL. Two flavours of head are injected server-side:

   - public pages (KI permalink, discover) get SEO metadata — OpenGraph +
     schema.org (see landing.agora.seo);
   - authoring/app pages (new, KI-by-id, article, admin) get a `robots noindex`
     head, since they are not public content and must not compete with the
     canonical permalink."
  (:require
   [clojure.java.io        :as io]
   [landing.agora.ki       :as ki]
   [landing.agora.seo      :as seo]
   [landing.endpoints.html :refer [html-middlewares]]))

(def ^:private public-template
  "The raw SPA shell, read once; a head is injected per request."
  (delay (some-> (io/resource "public/agora/ki.html")
                 slurp)))

(defn- html-response
  [body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"
             "Cache-Control" "public, max-age=600"}
   :body body})

(defn app-shell-response
  "Serve the SPA shell for an authoring/app route (new / KI-by-id / article /
  admin) with a noindex head."
  [req]
  (let [lang (or (get-in req [:path-params :lang]) "fr")]
    (html-response (seo/inject @public-template (seo/noindex-head "Agora") lang))))

(defn app-shell-route
  "Serve the app shell (noindex) at `prefix`. The frontend renders the right page
  from the URL."
  [prefix]
  ;; :conflicting — the `/agora/:lang/…` language segment overlaps the literal API
  ;; paths for the conflict detector; reitit's matcher prefers the literal.
  [prefix {:conflicting true
           :get {:swagger {:tags #{:agora}}
                 :handler app-shell-response
                 :middleware html-middlewares
                 :summary "Agora app shell (noindex)"}}])

(defn public-shell-response
  "Serve the public shell for a non-KI public page (discover / preferences) with
  generic OpenGraph metadata."
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
  OpenGraph and schema.org Article (name, description, datePublished, author,
  isBasedOn), server-rendered so crawlers and unfurlers see it without running the
  SPA."
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
