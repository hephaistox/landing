(ns landing.endpoints.ping
  "Simple ping endpoint returning pong"
  (:require
   [auto-web.middleware.rate-limit   :refer [make-rate-limiter stop-rate-limit]]
   [mount.core                       :refer [defstate]]
   [muuntaja.core                    :as m]
   [reitit.ring.middleware.exception :as exception]
   [reitit.ring.middleware.muuntaja  :as muuntaja]
   [ring.util.response               :as rr]))

(defstate rate-limiter
          :start (make-rate-limiter {:limit 3
                                     :window-ms 7000
                                     :name "landing.endpoints.ping"
                                     :cleanup-interval-ms 60000})
          :stop (stop-rate-limit rate-limiter))

(comment
  (mount.core/start)
  (mount.core/stop)
  ;;
)

(def ping-handler
  "Returns pong message"
  (fn [_http-request]
    (-> {:status 200
         :headers {}
         :body "pong"}
        (rr/content-type "text/plain"))))

(defn ping-route
  [prefix]
  [prefix {:get ping-handler
           :swagger {:tags #{:rest}}
           :summary "Simple endpoint returning pong"
           :muuntaja m/instance
           :middleware [(:middleware-fn rate-limiter)
                        ;; content-negotiation
                        muuntaja/format-negotiate-middleware
                        ;; encoding response body
                        muuntaja/format-response-middleware
                        ;; exception handling
                        exception/exception-middleware
                        ;; decoding request body
                        muuntaja/format-request-middleware]}])
