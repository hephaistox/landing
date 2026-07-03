(ns landing.handler
  "Handler turns an http http-request into a response"
  (:require
   [clojure.string                    :as str]
   [landing.agora.endpoints.article   :refer [article-route]]
   [landing.agora.endpoints.ki        :refer [by-major-route
                                              edit-ki-route
                                              inputs-route
                                              ki-collection-route
                                              ki-route]]
   [landing.agora.endpoints.lab       :refer [lab-shell-route public-shell-route]]
   [landing.endpoints.check-url       :refer [check-url-route]]
   [landing.endpoints.contact         :refer [contact-route]]
   [landing.endpoints.default-handler :refer [default-handler not-found-for-lang]]
   [landing.endpoints.exception       :refer [exception-route]]
   [landing.endpoints.html.admin-be   :refer [admin-route]]
   [landing.endpoints.ping            :refer [ping-route]]
   [landing.endpoints.plus            :refer [plus]]
   [landing.endpoints.resource        :refer [resource-handler]]
   [landing.endpoints.swagger         :refer [api-ep]]
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

(defn router
  []
  (rring/router [(ping-route "/ping")
                 (exception-route "/exception")
                 (root-redirect-route "/")
                 (lang-page-redirect-route "/index.html")
                 (lang-page-redirect-route "/404.html")
                 (legacy-articles-route "/articles")
                 (plus "/plus")
                 (admin-route "/all-kind-of-checks")
                 (contact-route "/contact")
                 (check-url-route "/check-url")
                 (ki-collection-route "/api/ki")
                 (by-major-route "/api/ki/by/:name/:major")
                 (ki-route "/api/ki/:id")
                 (edit-ki-route "/api/ki/:id/edit")
                 (inputs-route "/api/ki/:id/inputs")
                 (public-shell-route "/ki/:name/:major")
                 (public-shell-route "/discover")
                 (article-route "/api/article/:id")
                 (lab-shell-route "/lab/ki")
                 (lab-shell-route "/lab/ki/:id")
                 (lab-shell-route "/lab/article/:id")
                 (api-ep "/api")
                 (w3c-validate-route "/w3c-validate")]
                {}))

(defn handler
  []
  (rring/ring-handler (router)
                      (rring/routes resource-handler lang-fallback-handler default-handler)
                      {}))
