(ns user
  (:require
   [landing.server :as l]
   [mount.core     :as mount]))

::l/keep

(defn- running-tests?
  "True when the JVM was launched by cognitect.test-runner, so we can skip
  the Mount auto-start that would otherwise log a BindException."
  []
  (try (require 'cognitect.test-runner) true (catch Throwable _ false)))

(when-not (running-tests?)
  (try (mount/start)
       (catch Exception e
         (println "Unable to automatically start the web server")
         (println (pr-str e)))))
