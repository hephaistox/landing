(ns landing.handler
  "Handler turns an http http-request into a response"
  (:require
   [clojure.string                    :as str]
   [landing.endpoints.check-url       :refer [check-url-route]]
   [landing.endpoints.contact         :refer [contact-route]]
   [landing.endpoints.default-handler :refer [default-handler]]
   [landing.endpoints.exception       :refer [exception-route]]
   [landing.endpoints.html.admin-be   :refer [admin-route]]
   [landing.endpoints.ping            :refer [ping-route]]
   [landing.endpoints.plus            :refer [plus]]
   [landing.endpoints.resource        :refer [resource-handler]]
   [landing.endpoints.swagger         :refer [api-ep]]
   [landing.endpoints.w3c-validation  :refer [w3c-validate-route]]
   [reitit.ring                       :as rring]))

(def supported-langs #{"fr" "en"})
(def default-lang "fr")

(defn- cookie-header
  "Case-insensitive lookup of the Cookie header."
  [req]
  (some (fn [[k v]] (when (= "cookie" (str/lower-case (name k))) v)) (:headers req)))

(defn- cookie-lang
  "Return the `lang` cookie value if present and recognized. Accepts the
  legacy `:en`/`:fr` form (URL-encoded as `%3Aen` or raw) for back-compat."
  [req]
  (some-> (cookie-header req)
          (->> (re-find #"(?i)\blang=(?:%3A|:)?(en|fr)\b"))
          second
          str/lower-case))

(defn- accept-lang
  "Pick the first supported language from `Accept-Language`."
  [req]
  (some->> (some-> req
                   :headers
                   (get "accept-language"))
           (re-seq #"(?i)\b(en|fr)\b")
           first
           second
           str/lower-case))

(defn- pick-lang [req] (or (cookie-lang req) (accept-lang req) default-lang))

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
  "If the request path is `/fr/...` or `/en/...` and no resource matched,
  redirect to that language's index page instead of returning a generic 404."
  [req]
  (when-let [lang (second (re-find #"^/(fr|en)/" (str (:uri req))))]
    {:status 302
     :headers {"Location" (str "/" lang "/index.html")}}))

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
                 (api-ep "/api")
                 (w3c-validate-route "/w3c-validate")]
                {}))

(defn handler
  []
  (rring/ring-handler (router)
                      (rring/routes resource-handler lang-fallback-handler default-handler)
                      {}))
