(ns landing.handler
  "Handler turns an http http-request into a response"
  (:require
   [clojure.string                    :as str]
   [landing.agora.endpoints.admin     :refer [admin-routes]]
   [landing.agora.endpoints.article   :refer [article-route]]
   [landing.agora.endpoints.auth      :refer [auth-routes]]
   [landing.agora.endpoints.ki        :refer [by-major-route
                                              edit-ki-route
                                              inputs-route
                                              ki-collection-route
                                              ki-route
                                              translate-ki-route
                                              translate-suggest-route]]
   [landing.agora.endpoints.shell     :refer [app-shell-route
                                              ki-page-route
                                              public-shell-route
                                              sitemap-route]]
   [landing.endpoints.check-url       :refer [check-url-route]]
   [landing.endpoints.contact         :refer [contact-route]]
   [landing.endpoints.default-handler :refer [default-handler not-found-for-lang]]
   [landing.endpoints.html.admin-be   :refer [admin-route]]
   [landing.endpoints.ping            :refer [ping-route]]
   [landing.endpoints.resource        :refer [resource-handler]]
   [landing.endpoints.swagger         :refer [api-swagger]]
   [landing.endpoints.w3c-validation  :refer [w3c-validate-route]]
   [landing.language                  :refer [pick-lang supported-langs]]
   [reitit.ring                       :as rring]
   [ring.middleware.session           :refer [wrap-session]]
   [ring.middleware.session.cookie    :refer [cookie-store]]))

(defn- session-key
  "16-byte AES key for the signed session cookie, from SESSION_SECRET (padded /
  truncated to 16 bytes) or a dev default."
  []
  (-> (or (System/getenv "SESSION_SECRET") "agora-dev-secret!")
      (.getBytes "UTF-8")
      (java.util.Arrays/copyOf 16)))

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
  "Redirect `/agora` (and bare `/agora/:lang`) to the language-fixed discover page
  `/agora/<lang>/discover`. The language comes from the `:lang` path segment when
  present and supported, else from the browser (cookie → Accept-Language →
  default), mirroring how the landing site resolves language."
  [prefix]
  ;; :conflicting — `/agora/:lang` (2-seg wildcard) overlaps the literal
  ;; `/agora/sitemap.xml` for the detector; reitit's matcher prefers the literal.
  [prefix {:conflicting true
           :get {:handler (fn [req]
                            (let [seg (get-in req [:path-params :lang])
                                  lang (if (contains? supported-langs seg) seg (pick-lang req))]
                              {:status 302
                               :headers {"Location" (str "/agora/" lang "/discover")}}))}}])

(defn router
  []
  (rring/router [(ping-route "/ping")
                 (root-redirect-route "/")
                 (lang-page-redirect-route "/index.html")
                 (lang-page-redirect-route "/404.html")
                 (legacy-articles-route "/articles")
                 (admin-route "/all-kind-of-checks")
                 (contact-route "/contact")
                 (check-url-route "/check-url")
                 (agora-lang-redirect-route "/agora")
                 (sitemap-route "/agora/sitemap.xml")
                 (agora-lang-redirect-route "/agora/:lang")
                 (auth-routes "/agora/api/auth")
                 (admin-routes "/agora/api/admin")
                 (ki-collection-route "/agora/api/ki")
                 (by-major-route "/agora/api/ki/by/:name/:major")
                 (ki-route "/agora/api/ki/:id")
                 (edit-ki-route "/agora/api/ki/:id/edit")
                 (translate-ki-route "/agora/api/ki/:id/translate")
                 (translate-suggest-route "/agora/api/translate")
                 (inputs-route "/agora/api/ki/:id/inputs")
                 (ki-page-route "/agora/:lang/ki/:name/:major")
                 (public-shell-route "/agora/:lang/discover")
                 (public-shell-route "/agora/:lang/preferences")
                 (article-route "/agora/api/article/:id")
                 (app-shell-route "/agora/:lang/new")
                 (app-shell-route "/agora/:lang/ki/:id")
                 (app-shell-route "/agora/:lang/article/:id")
                 (app-shell-route "/agora/:lang/admin")
                 (api-swagger "/api")
                 (w3c-validate-route "/w3c-validate")]
                {}))

(defn handler
  []
  (-> (rring/ring-handler (router)
                          (rring/routes resource-handler lang-fallback-handler default-handler)
                          {})
      (wrap-session {:store (cookie-store {:key (session-key)})
                     :cookie-name "agora-session"
                     :cookie-attrs {:http-only true
                                    :same-site :lax}})))
