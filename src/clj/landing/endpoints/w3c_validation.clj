(ns landing.endpoints.w3c-validation
  "Check a page html and css"
  (:require
   [auto-web.middleware.rate-limit    :refer [make-rate-limiter stop-rate-limit]]
   [clj-http.client                   :as client]
   [clojure.string]
   [landing.pages.admin               :refer [w3c-validate-css w3c-validate-htmls]]
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
                                     :name "landing.endpoints.w3c-validation"
                                     :cleanup-interval-ms 60000})
          :stop (stop-rate-limit rate-limiter))

(defn w3c-validate-handler
  [request]
  (let [{:keys [css-id html-id domain]} (:query (:parameters request))]
    (if-let [css (->> css-id
                      keyword
                      (get w3c-validate-css))]
      (let [curl-data (client/get "https://jigsaw.w3.org/css-validator/validator/"
                                  {:max-redirects 2
                                   :cookie-policy :none
                                   :query-params {:uri (str domain "/" css)
                                                  :profile "css3"
                                                  :output "json"}
                                   :socket-timeout 1000
                                   :headers {:user-agent "Mozilla/5.0"
                                             :accept "application/json, text/html"}
                                   :connection-timeout 1000})]
        {:status 200
         :headers {}
         :body {:status (:status curl-data)
                :url (str domain "/" css)
                :curl-data (:body curl-data)}})
      (if-let [html (->> html-id
                         keyword
                         (get w3c-validate-htmls))]
        (let [curl-data (client/get "https://validator.w3.org/nu/"
                                    {:max-redirects 2
                                     :cookie-policy :none
                                     :query-params {:doc (str domain "/" html)
                                                    :out "json"}
                                     :socket-timeout 1000
                                     :headers {:user-agent "Mozilla/5.0"
                                               :accept "application/json, text/html"}
                                     :connection-timeout 1000})]
          {:status 200
           :headers {}
           :body {:status (:status curl-data)
                  :url (str domain "/" html)
                  :curl-data (:body curl-data)}})
        (rr/bad-request {:status 0})))))

(comment
  (w3c-validate-handler {:parameters {:query {:html-id "about"
                                              :domain "https://caumond.com"}}})
  (w3c-validate-handler {:parameters {:query {:css-id "colors-flat"
                                              :domain "https://caumond.com"}}})
  ;;
)

(defn w3c-validate-route
  [prefix]
  [prefix
   {:get {:coercion coercion
          :description "Validate a page"
          :handler w3c-validate-handler
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
          :operationId "w3c-validate"
          :parameters {:query [:or
                               [:map [:html-id :string] [:domain :string]]
                               [:map [:css-id :string] [:domain :string]]]}
          :responses {200 {:body [:map [:status :int] [:curl-data :string] [:url :string]]}}
          :summary "Curl the url to check its validity, css of html"
          :swagger {:tags #{:public-website}}}}])
