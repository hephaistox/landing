(ns user
  (:require
   [landing.server :as l]
   [mount.core     :as mount]))

::l/keep

(try
  (mount/start)
  (catch Exception e (println "Unable to automatically start the web server") (println (pr-str e))))
