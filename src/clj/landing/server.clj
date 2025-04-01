(ns landing.server
  "Start the landing page backend."
  (:require
   [auto-core.log     :as core-log]
   [landing.web-server]
   [mount.core        :as mount]
   [mount.tools.graph :as mount-graph])
  (:gen-class))

(defn -main
  "Main entry point for production, running production handler"
  [& _args]
  (try (core-log/info "Start landing")
       (core-log/trace "Component dependencies: " (mount-graph/states-with-deps))
       (mount/start)
       (catch Throwable e (core-log/fatal (ex-info "Unhandled exception" {:error e})))))

(comment
  ;; To start locally the webserver
  (-main {})
  ;
)
