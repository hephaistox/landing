(ns landing.web-server
  (:require
   [auto-core.log          :as core-log]
   [landing.handler]
   [landing.pages.error-be :refer [exception-response]]
   [mount.core             :refer [defstate]]
   [org.httpkit.server     :as http-kit]))

;; ********************************************************************************
;; Server
;; ********************************************************************************

;; Log uncaught exceptions in threads
(Thread/setDefaultUncaughtExceptionHandler
 (reify
  Thread$UncaughtExceptionHandler
    (uncaughtException [_ thread ex]
      (core-log/error-exception ex (str "Uncaught exception on" (.getName thread))))))

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
    (landing.handler/handler http-req)
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

(defstate http-server
          :start (try (core-log/info "Starting http-server")
                      (start-server app
                                    (or (some-> (System/getProperty "LANDING_PORT")
                                                Integer/parseInt)
                                        8080))
                      (catch Throwable e
                        (core-log/fatal-exception e "Unexpected error during web server starting")))
          :stop (try (stop-server #'http-server)
                     (catch Throwable e
                       (core-log/fatal-exception e "Unexepected error when closing http-server"))))
