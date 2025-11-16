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

(defstate rate-limiter
          :start (make-rate-limiter {:limit 40000
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

(defn- to-absolute-url
  [domain url]
  ;; If `url` is already absolute, return it unchanged.
  ;; Otherwise, use the browser to resolve it relative to the current location.
  (try (str (java.net.URL. (java.net.URL. domain) url)) (catch Exception _ url)))

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
        :else (let [url (to-absolute-url domain url)
                    curl-data (client/head url
                                           {:max-redirects 2
                                            :cookie-policy :none
                                            :socket-timeout 1000
                                            :headers {:user-agent "Mozilla/5.0"}
                                            :connection-timeout 1000})]
                {:status 200
                 :headers {}
                 :body {:status (:status curl-data)
                        :curl-data curl-data}}))
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
