(ns landing.endpoints.default-handler
  "Handler when route is not found, page not acceptable, or method disallowed,
  plus the exception responder used by `wrap-exception-handling`.

  - 404 and 500 use language-resolved static pages cached in memory via
    `landing.endpoints.cached-response`.
  - 405 (method not allowed) and 406 (not acceptable) are operational
    states usually seen by tooling/clients, not humans, so they return a
    small inline bilingual page with the correct status."
  (:require
   [auto-core.log                     :as core-log]
   [clojure.java.io                   :as io]
   [landing.endpoints.cached-response :as cr]
   [landing.language                  :as lang]
   [reitit.ring                       :as rring]))

;; ---------------------------------------------------------------------------
;; Static error pages (404 / 500)
;; ---------------------------------------------------------------------------

(defn- build-error-response
  "Construct a *prepared* error response for the given status and language."
  [status lang filename fallback-html]
  (cr/prepare {:status status
               :headers {"Content-Type" "text/html; charset=utf-8"}
               :body (or (some-> (io/resource (str "public/" lang "/" filename))
                                 slurp)
                         fallback-html)}))

(def ^:private prepared-error-page
  "[status lang filename fallback] → prepared response."
  (cr/cache-fn build-error-response
               (fn [status lang filename _] [status lang filename])))

(defn- static-error-response
  [req status filename fallback-html]
  (-> (prepared-error-page status (lang/pick-lang req) filename fallback-html)
      (cr/serve req)))

(defn- static-404-response
  [req]
  (static-error-response req 404 "404.html"
                         "<!doctype html><title>404</title><h1>Not found</h1>"))

(defn not-found-for-lang
  "Public 404 response that ignores cookie/Accept-Language and forces the
  given language. Useful when the URL path itself signals language intent
  (e.g. an unknown resource under `/en/...`)."
  [req lang]
  (-> (prepared-error-page 404 lang "404.html"
                           "<!doctype html><title>404</title><h1>Not found</h1>")
      (cr/serve req)))

(defn exception-response
  "Log the exception server-side and return the static `/<lang>/500.html` page
  with a 500 status. Never echoes exception details to the client."
  [req e]
  (core-log/error-exception e "Unhandled exception while serving request")
  (static-error-response req 500 "500.html"
                         "<!doctype html><title>500</title><h1>Unexpected error</h1>"))

;; ---------------------------------------------------------------------------
;; Inline error pages (405 / 406)
;; ---------------------------------------------------------------------------

(def ^:private inline-messages
  {405 {"fr" {:title "Méthode non autorisée"
              :detail "Cette ressource n'accepte pas cette méthode HTTP."}
        "en" {:title "Method not allowed"
              :detail "This resource does not accept that HTTP method."}}
   406 {"fr" {:title "Format non acceptable"
              :detail "Aucune représentation ne correspond aux préférences envoyées."}
        "en" {:title "Not acceptable"
              :detail "No representation matched the preferences you sent."}}})

(defn- inline-error-html
  [status lang]
  (let [{:keys [title detail]} (get-in inline-messages [status lang])]
    (str "<!doctype html>"
         "<html lang=\"" lang "\">"
         "<head><meta charset=\"utf-8\"><title>" status " — " title "</title>"
         "<meta name=\"robots\" content=\"noindex\"></head>"
         "<body><h1>" status " — " title "</h1><p>" detail "</p></body></html>")))

(defn- build-inline-response
  [status lang]
  (cr/prepare {:status status
               :headers {"Content-Type" "text/html; charset=utf-8"}
               :body (inline-error-html status lang)}))

(def ^:private prepared-inline-page
  "[status lang] → prepared response."
  (cr/cache-fn build-inline-response))

(defn- inline-error-response
  [req status]
  (-> (prepared-inline-page status (lang/pick-lang req))
      (cr/serve req)))

(defn- method-not-allowed-response [req] (inline-error-response req 405))
(defn- not-acceptable-response    [req] (inline-error-response req 406))

;; ---------------------------------------------------------------------------
;; Default handler
;; ---------------------------------------------------------------------------

(def default-handler
  (rring/create-default-handler {:not-found static-404-response
                                 :not-acceptable not-acceptable-response
                                 :method-not-allowed method-not-allowed-response}))
