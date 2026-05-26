(ns landing.web-server
  (:require
   [auto-core.log                     :as core-log]
   [landing.endpoints.default-handler :refer [exception-response]]
   [landing.handler]
   [mount.core                      :refer [defstate]]
   [org.httpkit.server              :as http-kit]))

;; ********************************************************************************
;; Server
;; ********************************************************************************

;; Log uncaught exceptions in threads
(let [out *out*
      err *err*]
  (Thread/setDefaultUncaughtExceptionHandler (reify
                                              Thread$UncaughtExceptionHandler
                                                (uncaughtException [_ thread ex]
                                                  (binding [*out* out
                                                            *err* err]
                                                    (println "Uncaught exception on "
                                                             (.getName thread))
                                                    (println "exception :" ex)
                                                    (flush))))))

(defn start-server
  "Generate an http-kit web server, listening at port `http-port` and serving `handler`."
  [handler http-port]
  (core-log/info "Starting Webserver component")
  (println (str "Webserver started on `http://localhost:" http-port "`"))
  (try (http-kit/run-server handler
                            {:port http-port
                             :join? false})
       (catch Exception e (core-log/error-exception e "Unable to start server"))))

(defn stop-server [server] (server) (core-log/info "Server stopped") server)

;; ******************************************************************************************
;; App
;; ******************************************************************************************

(defn app
  [http-req]
  (try
    ((landing.handler/handler) http-req)
    (catch Exception e
      (let
        [e-msg
         "While running app routes an exception has happened, look into :error to find more information"]
        (core-log/error-exception e e-msg))
      (exception-response http-req e))
    (catch Error e
      (core-log/fatal-exception
       e
       "While running app routes an error has happened, look into :error to find more information")
      (exception-response http-req e))))

(defn- resolve-port
  "Look up `LANDING_PORT` as an env var first, then JVM system property, else 8080."
  []
  (or (some-> (System/getenv "LANDING_PORT")
              Integer/parseInt)
      (some-> (System/getProperty "LANDING_PORT")
              Integer/parseInt)
      8080))

(defstate http-server
          :start (try (core-log/info "Starting http-server")
                      (start-server app (resolve-port))
                      (catch Throwable e
                        (core-log/fatal-exception e "Unexpected error during web server starting")))
          :stop (try (stop-server #'http-server)
                     (catch Throwable e
                       (core-log/fatal-exception e "Unexepected error when closing http-server"))))
