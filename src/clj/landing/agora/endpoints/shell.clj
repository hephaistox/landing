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
   [clojure.java.io                 :as io]
   [clojure.set                     :as set]
   [landing.agora.auth              :as auth]
   [landing.agora.document.engine   :as engine]
   [landing.agora.document.identity :as di]
   [landing.agora.seo               :as seo]
   [landing.endpoints.html          :refer [html-middlewares]]
   [landing.language                :as language]))

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
  ;; normalize the URL segment to a supported code — never inject it raw (XSS)
  (let [lang (language/normalize (get-in req [:path-params :lang]))]
    (html-response (seo/inject @public-template (seo/noindex-head "Agora") lang))))

(defn app-shell-route
  "Serve the app shell (noindex) at `prefix`. The frontend renders the right page
  from the URL."
  [prefix]
  [prefix {:get {:swagger {:tags #{:agora}}
                 :handler app-shell-response
                 :middleware html-middlewares
                 :summary "Agora app shell (noindex)"}}])

(defn public-shell-response
  "Serve the public shell for a non-KI public page (discover / preferences) with
  generic OpenGraph metadata."
  [req]
  (let [lang (language/normalize (get-in req [:path-params :lang]))
        head (seo/generic-head
              (seo/base-url req)
              lang
              "/discover"
              "Agora — Knowledge Items"
              "Reasoning made legible — a knowledge graph of challengeable reasoning steps.")]
    (html-response (seo/inject @public-template head lang))))

(defn- for-ssr
  "Shape a document for the SEO renderer: keep the identity `:lang` a keyword (the domain functions
  it feeds — `compose-statement`/`statement-prefix-of` — key on it; the renderer stringifies with
  `name` where a URL needs it) and expose a source's id as `:id`."
  [doc]
  (cond-> doc
    (:source doc) (update :source set/rename-keys {:source-id :id})))

(defn- ssr-body
  "The server-rendered permalink body for `doc`, with its input and successor edges resolved to real
  permalink links (crawlable) instead of bare ids."
  [doc-storage base lang doc doc-name]
  (seo/document-body base
                     lang
                     (assoc doc :inputs (engine/resolve-links doc-storage (:inputs doc)))
                     (engine/resolve-links doc-storage (:successors doc))
                     doc-name))

(defn ki-page-response
  "Serve the public KI permalink shell with per-KI SEO: title, description,
  OpenGraph and schema.org Article (name, description, datePublished, author,
  isBasedOn), server-rendered so crawlers and unfurlers see it without running the
  SPA. Reads from the injected `doc-storage`."
  [doc-storage]
  (fn [req]
    (let [{:keys [lang name major]} (:path-params req)
          lang (language/normalize lang)
          major-n (try (Integer/parseInt (str major)) (catch Exception _ 1))
          ;; the path segment is `<cid>~<slug>` (or bare cid); resolve by the cid
          name (di/cid-of name)
          ki (some-> (engine/read-by-major doc-storage
                                           {:type :ki
                                            :name name
                                            :lang (keyword lang)
                                            :major major-n})
                     for-ssr)
          base (seo/base-url req)
          head (if ki
                 (seo/ki-head base lang name major-n ki)
                 (seo/generic-head base lang (str "/ki/" name "/" major-n) "Agora" "Agora"))
          body (when ki (ssr-body doc-storage base lang ki name))]
      (html-response (seo/inject @public-template head lang body)))))

(defn home-page-response
  "Serve the Agora home/landing shell (`/agora/<lang>`) with its own SEO head —
  localized marketing title/description, canonical and hreflang alternates."
  [req]
  (let [lang (language/normalize (get-in req [:path-params :lang]))]
    (html-response (seo/inject @public-template (seo/home-head (seo/base-url req) lang) lang))))

(defn home-shell-route
  "Serve the home/landing shell at `prefix` (`/agora/:lang`)."
  [prefix]
  [prefix {:get {:swagger {:tags #{:agora}}
                 :handler home-page-response
                 :middleware html-middlewares
                 :summary "Agora home/landing (SEO head injected)"}}])

(defn public-shell-route
  "Serve the public shell for discover / preferences (generic OG metadata)."
  [prefix]
  [prefix {:get {:swagger {:tags #{:agora}}
                 :handler public-shell-response
                 :middleware html-middlewares
                 :summary "Public Agora page (discover / preferences)"}}])

(defn ki-page-route
  "Serve the public KI permalink shell with server-rendered SEO metadata, reading from `doc-storage`."
  [doc-storage prefix]
  [prefix {:get {:swagger {:tags #{:agora}}
                 :handler (ki-page-response doc-storage)
                 :middleware html-middlewares
                 :summary "Public KI permalink (SEO head injected)"}}])

(defn article-page-response
  "Serve the public article permalink shell with per-article SEO (title, description,
  OpenGraph, schema.org Article), server-rendered so crawlers see it without the SPA.
  Reads from the injected `doc-storage`."
  [doc-storage]
  (fn [req]
    (let [{:keys [lang name major]} (:path-params req)
          lang (language/normalize lang)
          major-n (try (Integer/parseInt (str major)) (catch Exception _ 1))
          ;; the path segment is `<cid>~<slug>` (or bare cid); resolve by the cid
          name (di/cid-of name)
          art (some-> (engine/read-by-major doc-storage
                                            {:type :article
                                             :name name
                                             :lang (keyword lang)
                                             :major major-n})
                      for-ssr)
          base (seo/base-url req)
          head (if art
                 (seo/article-head base lang name major-n art)
                 (seo/generic-head base lang (str "/article/" name "/" major-n) "Agora" "Agora"))
          body (when art (ssr-body doc-storage base lang art name))]
      (html-response (seo/inject @public-template head lang body)))))

(defn article-page-route
  "Serve the public article permalink shell with server-rendered SEO metadata, reading from
  `doc-storage`."
  [doc-storage prefix]
  [prefix {:get {:swagger {:tags #{:agora}}
                 :handler (article-page-response doc-storage)
                 :middleware html-middlewares
                 :summary "Public article permalink (SEO head injected)"}}])

(defn author-page-response
  "Serve the public author-profile shell with per-author SEO (name, canonical, OpenGraph,
  schema.org Person) and a server-rendered author→documents hub, so a crawler reaches all
  of a person's documents from one indexable page. Reads the person from `auth` and their
  documents from the injected `doc-storage`."
  [doc-storage]
  (fn [req]
    (let [{:keys [lang id]} (:path-params req)
          lang (language/normalize lang)
          base (seo/base-url req)
          profile (auth/author-profile id)
          head (if profile
                 (seo/author-head base lang id profile)
                 (seo/generic-head base lang (str "/author/" id) "Agora" "Agora"))
          body (when profile
                 (seo/author-body base lang profile (engine/author-documents doc-storage id)))]
      (html-response (seo/inject @public-template head lang body)))))

(defn author-page-route
  "Serve the public author profile shell (indexable, SEO head + author→documents hub)."
  [doc-storage prefix]
  [prefix {:get {:swagger {:tags #{:agora}}
                 :handler (author-page-response doc-storage)
                 :middleware html-middlewares
                 :summary "Public author profile (SEO head + hub)"}}])

(defn sitemap-response
  "sitemap.xml of every document permalink (KIs + articles) + the home/discover/articles
  hubs, generated from `doc-storage` so it reflects every publication."
  [doc-storage]
  (fn [req]
    {:status 200
     :headers {"Content-Type" "application/xml; charset=utf-8"
               "Cache-Control" "public, max-age=3600"}
     :body (seo/sitemap-xml (seo/base-url req) (engine/sitemap-rows doc-storage))}))

(defn sitemap-route
  [doc-storage prefix]
  [prefix {:get {:handler (sitemap-response doc-storage)
                 :middleware html-middlewares
                 :no-doc true
                 :summary "Agora sitemap"}}])
