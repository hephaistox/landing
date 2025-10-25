(ns landing.endpoints.swagger
  (:require
   [auto-web.middleware.rate-limit    :refer [make-rate-limiter stop-rate-limit]]
   [mount.core                        :refer [defstate]]
   [muuntaja.core                     :as m]
   [reitit.ring.coercion              :as coercion]
   [reitit.ring.middleware.exception  :as exception]
   [reitit.ring.middleware.multipart  :as multipart]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [reitit.swagger                    :as swagger]
   [reitit.swagger-ui                 :as swagger-ui]))

(defstate rate-limiter
          :start (make-rate-limiter {:limit 60
                                     :window-ms 60000
                                     :name "landing.endpoints.swagger"
                                     :cleanup-interval-ms 60000})
          :stop (stop-rate-limit rate-limiter))

(defn api-ep
  [path-prefix]
  [path-prefix {:no-doc true
                :muuntaja m/instance
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
                             coercion/coerce-response-middleware
                             ;; coercing request parameters
                             coercion/coerce-request-middleware
                             ;; multipart
                             multipart/multipart-middleware]}
   ["/swagger.json"
    {:get (swagger/create-swagger-handler)
     :no-doc true}]
   ["/api-docs/*"
    {:get (swagger-ui/create-swagger-ui-handler {:path (str path-prefix "/api-docs/")
                                                 :url (str path-prefix "/swagger.json")
                                                 :config {:validatorUrl nil}})
     :no-doc true}]])
