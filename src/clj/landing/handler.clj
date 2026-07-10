(ns landing.handler
  "Handler turns an http http-request into a response"
  (:require
   [clojure.string                    :as str]
   [env]
   [landing.agora.endpoints.admin     :refer [admin-routes]]
   [landing.agora.endpoints.auth      :refer [auth-routes]]
   [landing.agora.endpoints.author    :refer [author-routes]]
   [landing.agora.endpoints.document  :refer [document-routes translate-suggest-route]]
   [landing.agora.endpoints.people    :refer [people-routes]]
   [landing.agora.endpoints.shell     :refer [app-shell-route
                                              article-page-route
                                              author-page-route
                                              home-shell-route
                                              ki-page-route
                                              public-shell-route
                                              sitemap-route]]
   [landing.agora.endpoints.source    :refer [source-routes]]
   [landing.endpoints.check-url       :refer [check-url-route]]
   [landing.endpoints.contact         :refer [contact-route]]
   [landing.endpoints.default-handler :refer [default-handler not-found-for-lang]]
   [landing.endpoints.html.admin-be   :refer [admin-route]]
   [landing.endpoints.ping            :refer [ping-route]]
   [landing.endpoints.resource        :refer [resource-handler]]
   [landing.endpoints.swagger         :refer [api-swagger]]
   [landing.endpoints.w3c-validation  :refer [w3c-validate-route]]
   [landing.language                  :refer [pick-lang]]
   [reitit.ring                       :as rring]
   [ring.middleware.session           :refer [wrap-session]]
   [ring.middleware.session.cookie    :refer [cookie-store]]))

(defn- session-key
  "16-byte AES key for the signed session cookie. In production SESSION_SECRET is
  mandatory (≥16 chars): we fail fast rather than fall back to a shared, source
  visible dev key, which would let anyone forge a session cookie (e.g. the admin's
  user-id). In dev the default is fine."
  []
  (let [secret (System/getenv "SESSION_SECRET")]
    (when (and (= :prod env/env) (or (str/blank? secret) (< (count secret) 16)))
      (throw (ex-info "SESSION_SECRET must be set to at least 16 characters in production" {})))
    (-> (or secret "agora-dev-secret!")
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

(defn legacy-articles-route
  "Rewrite legacy `/articles/<slug>` URLs (no language prefix) to
  `/<lang>/articles/<slug>.html`. Only matches a single path segment
  so `/articles/foo/bar` is *not* silently rewritten to a non-existent
  resource."
  [prefix]
  [(str prefix "/:slug")
   {:get {:handler (fn [{:keys [path-params]
                         :as req}]
                     {:status 301
                      :headers {"Location" (str "/"
                                                (pick-lang req)
                                                "/articles/"
                                                (:slug path-params)
                                                (when-not (str/ends-with? (:slug path-params)
                                                                          ".html")
                                                  ".html"))}})}}])

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

(defn router
  []
  (rring/router
   [(ping-route "/ping")
    (root-redirect-route "/")
    (lang-page-redirect-route "/index.html")
    (lang-page-redirect-route "/404.html")
    (legacy-articles-route "/articles")
    (admin-route "/all-kind-of-checks")
    (contact-route "/contact")
    (check-url-route "/check-url")
    (agora-lang-redirect-route "/agora")
    (sitemap-route "/agora/sitemap.xml")
    (home-shell-route "/agora/:lang")
    (auth-routes "/agora/api/auth")
    (admin-routes "/agora/api/admin")
    ;; The whole KI/article API surface — one generic route set per object type.
    (document-routes "ki" "/agora/api/ki")
    (document-routes "article" "/agora/api/article")
    (author-routes "/agora/api/author")
    (people-routes "/agora/api/people")
    (source-routes "/agora/api/source")
    (translate-suggest-route "/agora/api/translate")
    (ki-page-route "/agora/:lang/ki/:name/:major")
    (public-shell-route "/agora/:lang/discover")
    (public-shell-route "/agora/:lang/preferences")
    (public-shell-route "/agora/:lang/articles")
    (article-page-route "/agora/:lang/article/:name/:major")
    (app-shell-route "/agora/:lang/new")
    (app-shell-route "/agora/:lang/ki/:id")
    (app-shell-route "/agora/:lang/article/:id")
    (author-page-route "/agora/:lang/author/:id")
    (app-shell-route "/agora/:lang/admin")
    (api-swagger "/api")
    (w3c-validate-route "/w3c-validate")]
   {}))

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

(defn wrap-strip-trailing-slash
  "Normalize the request URI by dropping any trailing slash (except the root `/`) before
  routing, so `/agora/en/discover/` resolves the same route as `/agora/en/discover`
  instead of 404-ing. Each page's `<link rel=canonical>` still points at the slash-less
  form, so search engines consolidate on one URL."
  [handler]
  (fn [req]
    (let [uri (:uri req)]
      (handler (if (and (> (count uri) 1) (str/ends-with? uri "/"))
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
      ;; outermost: normalize the URI (drop trailing slash) before any host-redirect
      ;; or routing sees it
      wrap-strip-trailing-slash))
