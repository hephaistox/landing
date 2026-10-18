(ns landing.handler
  "Handler turns an http http-request into a response"
  (:require
   [clojure.string                    :as str]
   [env]
   [landing.endpoints.check-url       :refer [check-url-route]]
   [landing.endpoints.contact         :refer [contact-route]]
   [landing.endpoints.default-handler :refer [default-handler not-found-for-lang]]
   [landing.endpoints.html.admin-be   :refer [admin-route]]
   [landing.endpoints.ping            :refer [ping-route]]
   [landing.endpoints.resource        :refer [resource-handler]]
   [landing.endpoints.swagger         :refer [api-swagger]]
   [landing.endpoints.w3c-validation  :refer [w3c-validate-route]]
   [landing.language                  :refer [pick-lang]]
   [reitit.ring                       :as rring]))

(defn root-redirect-route
  "Redirect `/` to the language-specific static index page.
  Honors `lang` cookie, then `Accept-Language`, then defaults to `fr`."
  [prefix]
  [prefix {:get {:handler (fn [req]
                            {:status 302
                             :headers {"Location" (str "/" (pick-lang req) "/index.html")}})}}])

(defn agora2-redirect-route
  "Redirect `/agora2` to its language-specific static index page, the language picked as for `/`."
  [prefix]
  [prefix {:get {:handler (fn [req]
                            {:status 302
                             :headers {"Location"
                                       (str "/agora2/" (pick-lang req) "/index.html")}})}}])

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

(defn router
  []
  (rring/router [(ping-route "/ping")
                 (root-redirect-route "/")
                 (agora2-redirect-route "/agora2")
                 (lang-page-redirect-route "/index.html")
                 (lang-page-redirect-route "/404.html")
                 (legacy-articles-route "/articles")
                 (admin-route "/all-kind-of-checks")
                 (contact-route "/contact")
                 (check-url-route "/check-url")
                 (api-swagger "/api")
                 (w3c-validate-route "/w3c-validate")]))

(defn- content-security-policy
  "What a page may load, given the running `env`. Everything we serve is same-origin, so the policy
  is `'self'` plus two deliberate widenings:
   - `style-src 'unsafe-inline'` — the pages carry `style=` attributes. Injected CSS is a far
     smaller problem than injected script, and removing them is a refactor, not a security fix.
   - `frame-ancestors 'none'` — nobody frames us, which is `X-Frame-Options` for modern browsers.

  Outside production, `'unsafe-eval'` is added: a shadow-cljs **dev** build loads namespaces through
  `goog.globalEval`, which is how hot reload works. The **release** build contains no `eval` in any
  form, so production keeps the strict policy — the directive that makes the whole thing worth
  having. Pure, so both variants are testable without an environment."
  [env]
  (str "default-src 'self'; "
       "script-src 'self'"
       (when (not= :prod env) " 'unsafe-eval'")
       "; "
       "style-src 'self' 'unsafe-inline'; " "img-src 'self' data:; "
       "font-src 'self'; " "connect-src 'self'; "
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
  routing, so `/fr/articles/` resolves the same route as `/fr/articles`
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
      ;; above the router so every response carries them — a static asset, a redirect and a 404
      ;; included
      wrap-security-headers
      ;; outermost: normalize the URI (drop trailing slash) before routing sees it
      wrap-strip-trailing-slash))
