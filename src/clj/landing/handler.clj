(ns landing.handler
  "Handler turns an http http-request into a response"
  (:require
   [clojure.string                      :as str]
   [env]
   [landing.agora.document.cached-db    :as dcd]
   [landing.agora.endpoints.admin       :refer [admin-routes]]
   [landing.agora.endpoints.auth        :refer [auth-routes]]
   [landing.agora.endpoints.author      :refer [author-routes]]
   [landing.agora.endpoints.document    :refer [document-routes]]
   [landing.agora.endpoints.people      :refer [people-routes]]
   [landing.agora.endpoints.publication :refer [publication-routes]]
   [landing.agora.endpoints.shell       :refer [app-shell-route
                                                article-page-route
                                                author-page-route
                                                beta-shell-route
                                                home-shell-route
                                                ki-page-route
                                                public-shell-route
                                                sitemap-route]]
   #_[landing.endpoints.check-url :refer [check-url-route]]
   [landing.endpoints.contact           :refer [contact-route]]
   [landing.endpoints.default-handler   :refer [default-handler not-found-for-lang]]
   #_[landing.endpoints.html.admin-be :refer [admin-route]]
   [landing.endpoints.ping              :refer [ping-route]]
   [landing.endpoints.resource          :refer [resource-handler]]
   [landing.endpoints.swagger           :refer [api-swagger]]
   #_[landing.endpoints.w3c-validation :refer [w3c-validate-route]]
   [landing.language                    :refer [languages pick-lang]]
   [reitit.ring                         :as rring]
   [ring.middleware.session             :refer [wrap-session]]
   [ring.middleware.session.cookie      :refer [cookie-store]]))

(def ^:private dev-session-secret
  "Session secret used outside production. It is in the source, so anyone holding it can forge a
  cookie — which is why production must never reach it (see `session-key`)."
  "agora-dev-secret!")

(defn- session-key
  "16-byte AES key for the signed session cookie. In production SESSION_SECRET is
  mandatory (≥16 chars, and never `dev-session-secret`): we fail fast rather than fall
  back to a shared, source visible dev key, which would let anyone forge a session
  cookie (e.g. the admin's user-id). In dev the default is fine."
  []
  (let [secret (System/getenv "SESSION_SECRET")]
    (when (and (= :prod env/env)
               (or (str/blank? secret) (< (count secret) 16) (= secret dev-session-secret)))
      (throw (ex-info "SESSION_SECRET must be set to at least 16 characters in production" {})))
    ;; blank is unset, not a key — `or` alone would hand back "", a blank string being truthy
    (-> (if (str/blank? secret) dev-session-secret secret)
        (.getBytes "UTF-8")
        (java.util.Arrays/copyOf 16))))

(defn root-redirect-route
  "Redirect `/` to the language-specific static index page.
  Honors `lang` cookie, then `Accept-Language`, then defaults to `fr`."
  [prefix]
  [prefix {:get {:handler (fn [req]
                            {:status 302
                             :headers {"Location" (str "/" (pick-lang req) "/index.html")}})}}])

(defn lang-page-redirect-route
  "Redirect a top-level static page (e.g. `/index.html`, `/404.html`) to its
  language-prefixed location."
  [path]
  [path {:get {:handler (fn [req]
                          {:status 302
                           :headers {"Location" (str "/" (pick-lang req) path)}})}}])

(def ^:private legacy-slug-re
  "Shape a legacy article slug must have to be echoed into a `Location` header: letters, digits, and
  `.`/`_`/`-`. Every real article name matches (`who-are-we`, `legal-notice`, …).

  This is a **header-injection guard**, not tidiness. Reitit percent-decodes a path param, so
  `/articles/foo%0d%0aSet-Cookie:%20x=1` would otherwise put a raw CRLF inside the redirect's
  `Location` — and http-kit writes header values as given, so the attacker's line becomes a real
  header of our response. Anything outside the shape 404s instead."
  #"^[A-Za-z0-9._-]+$")

(defn legacy-articles-route
  "Rewrite legacy `/articles/<slug>` URLs (no language prefix) to
  `/<lang>/articles/<slug>.html`. Only matches a single path segment
  so `/articles/foo/bar` is *not* silently rewritten to a non-existent
  resource, and only a `legacy-slug-re` slug is redirected at all."
  [prefix]
  [(str prefix "/:slug")
   {:get {:handler (fn [{:keys [path-params]
                         :as req}]
                     (let [slug (str (:slug path-params))]
                       (if (re-matches legacy-slug-re slug)
                         {:status 301
                          :headers {"Location" (str "/"
                                                    (pick-lang req)
                                                    "/articles/"
                                                    slug
                                                    (when-not (str/ends-with? slug ".html")
                                                      ".html"))}}
                         (not-found-for-lang req (pick-lang req)))))}}])

(defn lang-fallback-handler
  "When the request path is `/fr/...` or `/en/...` but no resource matched,
  serve the language-appropriate 404 directly. The path itself signals
  language intent, so we use it instead of the cookie/Accept-Language
  heuristic used by `default-handler`."
  [req]
  (when-let [lang (second (re-find #"^/(fr|en)/" (str (:uri req))))] (not-found-for-lang req lang)))

(defn agora-lang-redirect-route
  "Redirect bare `/agora` to the language-fixed landing/home page `/agora/<lang>`. The
  language comes from the browser (cookie → Accept-Language → default), mirroring how
  the landing site resolves language. (`/agora/<lang>` itself is served as the SPA
  shell — see the router.)"
  [prefix]
  [prefix {:get {:handler (fn [req]
                            {:status 302
                             :headers {"Location" (str "/agora/" (pick-lang req))}})}}])

(def ^:private lang-injector
  "Reitit middleware for the enumerated language shells: copy the route's declared `:lang`
  (route data) into `:path-params`, so the shell handlers keep reading `(:path-params req)`
  even though the language segment is now a literal (`fr`/`en`), not a `:lang` wildcard.
  Compiles to nothing for routes without `:lang` (the API, sitemap, …) — a no-op there."
  {:name ::lang-injector
   :compile (fn [{:keys [lang]} _]
              (when lang
                (fn [handler] (fn [req] (handler (assoc-in req [:path-params :lang] lang))))))})

(defn- agora-shell-routes
  "Path suffix (under `/agora/<lang>`) → the route builder for each public language shell.
  Enumerated per language in `agora-lang-routes` so the language segment is a **literal**,
  not a wildcard — a `:lang` wildcard would overlap the literal `/agora/api…` and
  `/agora/sitemap.xml`, which is what forced reitit onto the slow quarantine router. The
  permalink shells read documents, so `doc-storage` is injected into their builders."
  [doc-storage]
  [["" home-shell-route]
   ["/ki/:name/:major" (partial ki-page-route doc-storage)]
   ["/article/:name/:major" (partial article-page-route doc-storage)]
   ;; one browse feed for every document type — the SPA reads `:type` (ki | article | source)
   ["/documents/:type" public-shell-route]
   ["/discover" public-shell-route]
   ["/articles" public-shell-route]
   ["/preferences" public-shell-route]
   ["/authors" public-shell-route]
   ["/faq" public-shell-route]
   ["/beta" beta-shell-route]
   ["/publications" app-shell-route]
   ["/new" app-shell-route]
   ["/ki/:id" app-shell-route]
   ["/article/:id" app-shell-route]
   ["/author/:id" (partial author-page-route doc-storage)]
   ["/admin" app-shell-route]
   ["/publication/:id" app-shell-route]
   ["/publication/:id/graph" app-shell-route]])

(defn- agora-lang-routes
  "Every public shell, enumerated once per supported language, with the literal language
  baked into both the path and the route data (`:lang`). An unsupported language matches no
  route and 404s — cleaner than a wildcard silently falling back to a default."
  [doc-storage]
  (for [lang languages
        [suffix route-fn] (agora-shell-routes doc-storage)
        :let [[path data] (route-fn (str "/agora/" lang suffix))]]
    ;; the language and public-vs-app kind are properties of the mounting, so the
    ;; per-language Swagger tag — `agora-html-<lang>` (public) / `agora-app-<lang>`
    ;; (noindex app shell) — is set here, not in the generic shell builders
    [path
     (assoc data
            :lang lang
            :swagger {:tags #{(keyword (str "agora-" (if (= route-fn app-shell-route) "app" "html")
                                            "-" (name lang)))}})]))

(defn router
  []
  ;; `cached-db` is the concrete `DocumentStorage` (a DB-backed read cache); it is injected here,
  ;; the composition root, into every endpoint that reads documents, so the read stack stays
  ;; backend-agnostic.
  (rring/router (into [(ping-route "/ping")
                       (root-redirect-route "/")
                       (lang-page-redirect-route "/index.html")
                       (lang-page-redirect-route "/404.html")
                       (legacy-articles-route "/articles")
                       ;; TEMPORAIRE — la page de diagnostic et ses deux endpoints sont retirés du
                       ;; routeur le temps de décider s'ils passent derrière l'authentification
                       ;; admin. Ils sont ouverts à tous et font faire au serveur des requêtes
                       ;; sortantes ; la page n'a d'intérêt que pour nous. Décommenter ici et plus
                       ;; bas (w3c-validate) pour les remettre.
                       #_(admin-route "/all-kind-of-checks")
                       (contact-route "/contact")
                       #_(check-url-route "/check-url")
                       (agora-lang-redirect-route "/agora")
                       (sitemap-route dcd/document-cached-db "/agora/sitemap.xml")
                       (auth-routes "/agora/api/auth")
                       (admin-routes "/agora/api/admin")
                       ;; The whole KI/article API surface — one generic route set per object type.
                       (document-routes dcd/document-cached-db "/agora/api/documents")
                       (author-routes dcd/document-cached-db "/agora/api/author")
                       (people-routes "/agora/api/people")
                       (publication-routes dcd/document-cached-db "/agora/api/publication")
                       (api-swagger "/api")
                       ;; TEMPORAIRE — voir la note plus haut (page de diagnostic)
                       #_(w3c-validate-route "/w3c-validate")]
                      (agora-lang-routes dcd/document-cached-db))
                {:data {:middleware [lang-injector]}}))

(defn wrap-agora-canonical-host
  "In production, 301-redirect Agora URLs (`/agora…`) reached on any non-canonical
  host to the canonical origin (OAUTH_BASE_URL, e.g. https://hephaistox.fr), so
  links, logins and SEO all consolidate on one domain. Scoped to `/agora` so the
  localized marketing site on the other hephaistox domains is untouched. No-op in
  dev. The redirect target is a fixed constant, so this is never an open redirect."
  [handler]
  (let [base (-> (or (System/getenv "OAUTH_BASE_URL") "https://hephaistox.fr")
                 (str/replace #"/+$" ""))
        canonical-host (-> base
                           (str/replace #"^https?://" "")
                           (str/replace #"/.*$" ""))
        prod? (= :prod env/env)]
    (fn [req]
      (let [host (get-in req [:headers "host"])
            uri (str (:uri req))]
        (if (and prod? host (not= host canonical-host) (str/starts-with? uri "/agora"))
          {:status 301
           :headers {"Location" (str base uri (when-let [q (:query-string req)] (str "?" q)))}}
          (handler req))))))

(def ^:private inline-script-hash
  "CSP hash of the one inline `<script>` we ship (`resources/public/agora/ki.html`, which flags the
  document as JS-capable before first paint). Hashing it is what lets `script-src` stay free of
  `'unsafe-inline'` — the directive that would otherwise make the whole policy decorative. **Recompute
  this if that script changes**, otherwise the browser blocks it."
  "'sha256-sa2BD07tH4oO53uT1B5vNSLM2+gcrREM4WTXttKp6oU='")

(defn- content-security-policy
  "What a page may load, given the running `env`. Everything we serve is same-origin, so the policy
  is `'self'` plus four deliberate widenings:
   - `style-src 'unsafe-inline'` — the pages and the server-rendered Agora body carry `style=`
     attributes. Injected CSS is a far smaller problem than injected script, and removing them is a
     refactor, not a security fix.
   - `img-src https:` — an account's avatar is still hotlinked from the OAuth provider.
   - `worker-src blob:` — the ALTCHA captcha solves its proof-of-work in a worker it builds itself.
   - `frame-ancestors 'none'` — nobody frames us, which is `X-Frame-Options` for modern browsers.

  Outside production, `'unsafe-eval'` is added: a shadow-cljs **dev** build loads namespaces through
  `goog.globalEval`, which is how hot reload works. The **release** build contains no `eval` in any
  form, so production keeps the strict policy — the directive that makes the whole thing worth
  having. Pure, so both variants are testable without an environment."
  [env]
  (str "default-src 'self'; "
       "script-src 'self' "
       inline-script-hash
       (when (not= :prod env) " 'unsafe-eval'")
       "; " "style-src 'self' 'unsafe-inline'; "
       "img-src 'self' data: https:; " "font-src 'self'; "
       "connect-src 'self'; " "worker-src 'self' blob:; "
       "object-src 'none'; " "base-uri 'self'; "
       "form-action 'self'; " "frame-ancestors 'none'"))

(def ^:private csp-header
  "The policy for this deployment, built once."
  (content-security-policy env/env))

(defn wrap-security-headers
  "Add the response headers a browser needs to defend the page.

  The CSP ships **report-only** for now: it is tuned by reading the source, not by exercising every
  screen, and an over-tight policy silently breaks features (the captcha worker, an avatar) rather
  than failing loudly. Report-only puts every violation in the browser console with nothing broken —
  the designed way to converge — after which the header name loses its `-Report-Only` suffix.

  The other four are unconditional and carry no such risk. HSTS is production-only, and without
  `includeSubDomains`: it would otherwise bind every subdomain of the apex to HTTPS, including ones
  this app knows nothing about."
  [handler]
  (fn [req]
    ;; `some->` so a nil (no response at all) stays nil rather than becoming a status-less map
    (some-> (handler req)
            (update :headers
                    (fn [h]
                      (cond-> (assoc h
                                     "X-Content-Type-Options" "nosniff"
                                     "Referrer-Policy" "strict-origin-when-cross-origin"
                                     "X-Frame-Options" "SAMEORIGIN"
                                     "Content-Security-Policy-Report-Only" csp-header)
                        (= :prod env/env) (assoc "Strict-Transport-Security"
                                                 "max-age=31536000")))))))

(defn wrap-strip-trailing-slash
  "Normalize the request URI by dropping any trailing slash (except the root `/`) before
  routing, so `/agora/en/discover/` resolves the same route as `/agora/en/discover`
  instead of 404-ing. Each page's `<link rel=canonical>` still points at the slash-less
  form, so search engines consolidate on one URL. The Swagger UI is exempt — it is served
  from the *directory* path `/api/api-docs/`, whose trailing slash is significant (stripping
  it 404s the docs)."
  [handler]
  (fn [req]
    (let [uri (:uri req)]
      (handler (if (and (> (count uri) 1)
                        (str/ends-with? uri "/")
                        (not (str/starts-with? uri "/api/api-docs")))
                 (assoc req :uri (str/replace uri #"/+$" ""))
                 req)))))

(defn handler
  []
  (-> (rring/ring-handler (router)
                          (rring/routes resource-handler lang-fallback-handler default-handler)
                          {})
      (wrap-session {:store (cookie-store {:key (session-key)})
                     :cookie-name "agora-session"
                     :cookie-attrs {:http-only true
                                    :same-site :lax
                                    ;; HTTPS-only in prod so the session cookie
                                    ;; never travels over plain HTTP
                                    :secure (= :prod env/env)}})
      wrap-agora-canonical-host
      ;; above the router so every response carries them — a static asset, a redirect and a 404
      ;; included
      wrap-security-headers
      ;; outermost: normalize the URI (drop trailing slash) before any host-redirect
      ;; or routing sees it
      wrap-strip-trailing-slash))
