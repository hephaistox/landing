(ns landing.endpoints.check-url
  "Check an url"
  (:require
   [auto-web.middleware.rate-limit    :refer [make-rate-limiter stop-rate-limit]]
   [clj-http.client                   :as client]
   [clojure.string]
   [landing.pages.admin               :refer [links]]
   [mount.core                        :refer [defstate]]
   [muuntaja.core                     :as m]
   [reitit.coercion.malli             :refer [coercion]]
   [reitit.ring.coercion              :as rcoercion]
   [reitit.ring.middleware.exception  :as exception]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [ring.middleware.keyword-params    :refer [wrap-keyword-params]]
   [ring.util.response                :as rr]))

(defstate
 rate-limiter
 "Per-IP throttle. The admin page fires one request per manifest link on load (44 today), so a
          few hundred a minute covers a reload or two; the previous 40 000 was no limit at all, and
          each request makes the server open an outbound connection."
 :start (make-rate-limiter {:limit 300
                            :window-ms 60000
                            :name "landing.endpoints.check-url"
                            :cleanup-interval-ms 60000})
 :stop (stop-rate-limit rate-limiter))

(defn search
  [link-id origin]
  (->> links
       (filter #(and (= (:link-id %) link-id) (= (:origin %) origin)))
       first
       :url))

(comment
  (search :contact "landing.article.rivalis")
  (search :prod-http-fr-www "landing.admin")
  ; link-id=w3-schools&origin=landing.pages.structure&domain=http://localhost:8080/
)

(def ^:private our-hosts
  "Hosts a **relative** manifest link may be resolved against — our own sites, plus the local dev
  server. Same set as the CORS origins (`env/cors-parameters`), for the same reason: these are the
  deployments this endpoint is meant to probe."
  [#"^localhost$" #"^127\.0\.0\.1$" #"(^|\.)hephaistox\.(com|fr)$" #"(^|\.)cleverapps\.io$"])

(defn- absolute?
  "True when `url` already carries a scheme, so no base is needed to resolve it."
  [url]
  (boolean (re-find #"^[a-zA-Z][a-zA-Z0-9+.\-]*:" (str url))))

(defn- our-domain?
  "True when `domain` is an http(s) URL on one of `our-hosts`. Anything else — another host, or a
  scheme like `file:` — is refused rather than resolved."
  [domain]
  (try (let [u (java.net.URL. (str domain))]
         (and (contains? #{"http" "https"} (.getProtocol u))
              (boolean (some #(re-find % (str (.getHost u))) our-hosts))))
       (catch Exception _ false)))

(defn- target-url
  "The URL to probe, or nil when the request must be refused.

  An **absolute** manifest link is used as is: it comes from our own source, so its host is one we
  chose to check (LinkedIn, the W3C…). A **relative** one has to be resolved against the caller's
  `domain`, and that is the only client-controlled part of the target — so it must name one of our
  own sites. Without that check the endpoint probes any host and reports the status back, which is a
  scanner for whatever the server can reach, internal addresses included."
  [domain url]
  (cond
    (absolute? url) url
    (our-domain? domain) (try (str (java.net.URL. (java.net.URL. (str domain)) url))
                              (catch Exception _ nil))
    :else nil))

(defn check-url-handler
  [request]
  (let [{:keys [link-id origin domain]} (:query (:parameters request))
        link-id (keyword link-id)]
    (if-let [url (search link-id origin)]
      (cond
        (clojure.string/starts-with? url "mailto:") {:status 200
                                                     :headers {}
                                                     :body {:status 200
                                                            :curl-data {:url url}}}
        (or (= :linkedin-anthony link-id) (= :linkedin-mati link-id)) {:status 200
                                                                       :headers {}
                                                                       :body {:status 200
                                                                              :curl-data {:url
                                                                                          url}}}
        :else (if-let [target (target-url domain url)]
                (let [curl-data (client/head target
                                             {:max-redirects 2
                                              :cookie-policy :none
                                              :socket-timeout 1000
                                              :headers {:user-agent "Mozilla/5.0"}
                                              :connection-timeout 1000})]
                  {:status 200
                   :headers {}
                   :body {:status (:status curl-data)
                          :curl-data curl-data}})
                (rr/bad-request {:status 0})))
      (rr/bad-request {:status 0}))))

(comment
  (check-url-handler {:parameters {:query {:link-id "contact"
                                           :origin "landing.article.rivalis"
                                           :domain "http://localhost:8080/"}}})
  "https://www.linkedin.com/in/anthony-caumond-a365b15/"
  "https://agilemanifesto.org/iso/fr/manifesto.html"
  (client/head
   "http://localhost:8080"
   {:max-redirects 2
    :headers
    {:user-agent
     "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"}
    :cookie-policy :none
    :socket-timeout 2000
    :connection-timeout 2000})
  (client/get
   "https://agilemanifesto.org/iso/fr/manifesto.html"
   {:max-redirects 2
    :headers
    {:user-agent
     "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"}
    :cookie-policy :none
    :socket-timeout 2000
    :connection-timeout 2000})
  ;;
)

(defn check-url-route
  [prefix]
  [prefix
   {:get {:coercion coercion
          :description "Check the url passed as a parameter is a valid url"
          :handler check-url-handler
          :middleware [(:middleware-fn rate-limiter)
                       ;; query-params & form-params
                       parameters/parameters-middleware
                       ;; content-negotiation
                       muuntaja/format-negotiate-middleware
                       ;; encoding response body
                       muuntaja/format-response-middleware
                       ;; exception handling
                       exception/exception-middleware
                       ;; decoding request body
                       muuntaja/format-request-middleware
                       ;; coercing response bodys
                       rcoercion/coerce-response-middleware
                       ;; coercing request parameters
                       rcoercion/coerce-request-middleware
                       wrap-keyword-params]
          :muuntaja m/instance
          :operationId "check-url"
          :parameters {:query [:map [:link-id :keyword] [:origin :string] [:domain :string]]}
          :responses {200 {:body [:map [:status :int] [:curl-data :map]]}}
          :summary "Curl the url to check its validity"
          :swagger {:tags #{:public-website}}}}])
