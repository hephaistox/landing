(ns user
  (:require
   [landing.server :as l]
   [mount.core     :as mount]))

::l/keep

(defn start
  "Start the app's mount states — the web server and the Agora connection pool."
  []
  (mount/start))

(defn stop
  "Stop them, closing the pool and releasing the port.

  What a namespace reload must call **first**. `tools.namespace/refresh` removes a namespace before
  reloading it, so the var holding the running HikariCP pool disappears without `:stop` ever running:
  the pool is never closed, its housekeeping thread keeps it alive, and the reloaded `user` starts a
  second one. The addon caps at five connections across dev/la/prod, so a few refreshes are enough to
  starve it — and a failing `/me` reads on screen as being logged out."
  []
  (mount/stop))

;; Reload scope, when `tools.namespace` is on the classpath — CIDER brings it, a plain `clj` run does
;; not, hence the guard. Without it a refresh walks every directory on the classpath, `env/seed` and
;; `test` included: slower, and it evaluates seed namespaces that have no business being reloaded.
(try (require 'clojure.tools.namespace.repl)
     ((resolve 'clojure.tools.namespace.repl/set-refresh-dirs) "src" "env/dev")
     (catch Throwable _ nil))

(defn- running-tests?
  "True when the JVM was launched by cognitect.test-runner, so we can skip
  the Mount auto-start that would otherwise log a BindException."
  []
  (try (require 'cognitect.test-runner) true (catch Throwable _ false)))

;; Auto-start on load, so a bare `clj -M:env-dev` REPL comes up running. A CIDER refresh reloads this
;; file too, so this is what restarts the app there; the `after` hook below is then a no-op.
(when-not (running-tests?)
  (try (start)
       (catch Exception e
         (println "Unable to automatically start the web server")
         (println (pr-str e)))))
