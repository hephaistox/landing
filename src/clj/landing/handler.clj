(ns landing.handler
  "Handler turns an http http-request into a response"
  (:require
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

(defn- cookie-lang
  "Return \"fr\" or \"en\" if a `lang` cookie is present, else nil."
  [req]
  (some-> req
          :headers
          (get "cookie")
          (->> (re-find #"lang=:?(en|fr)"))
          second))

(defn root-redirect-route
  "Redirect `/` to the language-specific static index page.
  Honors a `lang` cookie; defaults to `fr`."
  [prefix]
  [prefix {:get {:handler (fn [req]
                            {:status 302
                             :headers {"Location"
                                       (str "/" (or (cookie-lang req) "fr") "/index.html")}})}}])

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
