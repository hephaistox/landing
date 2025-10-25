(ns landing.endpoints.plus
  "A toy exemple endpoint for adding two integers"
  (:require
   [auto-web.middleware.rate-limit    :refer [make-rate-limiter stop-rate-limit]]
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
          :start (make-rate-limiter {:limit 60
                                     :window-ms 60000
                                     :name "landing.endpoints.plus"
                                     :cleanup-interval-ms 60000})
          :stop (stop-rate-limit rate-limiter))

(defn plus-handler
  [request]
  (let [{:keys [x y]} (:query (:parameters request))]
    (-> {:status 200
         :headers {}
         :body {:total (+ (or x 0) (or y 0))}}
        (rr/content-type "text/plain"))))

(defn plus
  [prefix]
  [prefix
   {:get {:coercion coercion
          :description "Takes `x` and `y` query-params and adds them together"
          :handler plus-handler
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
          :operationId "addTwoNumbers"
          :parameters {:query [:map [:x :int] [:y :int]]}
          :responses {200 {:body [:map [:total :int]]}}
          :summary "Adds numbers together"
          :swagger {:tags #{:math}}}}])
