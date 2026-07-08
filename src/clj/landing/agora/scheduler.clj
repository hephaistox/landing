(ns landing.agora.scheduler
  "Daily in-process rebuild of the Agora successor index (a cache), so any drift from
  incremental updates self-heals. Needs no external cron; started/stopped with the
  app via Mount."
  (:require
   [auto-core.log          :as core-log]
   [landing.agora.document :as document]
   [mount.core             :refer [defstate]])
  (:import (java.util.concurrent Executors ScheduledExecutorService TimeUnit)))

(defn- rebuild!
  []
  (try (core-log/info "Agora: rebuilding successor index")
       (document/rebuild-successor-index!)
       (catch Throwable e (core-log/error-exception e "Agora: successor index rebuild failed"))))

(defn- start
  "Schedule the rebuild once every 24h (first run 24h after boot — the index is
  already fresh at startup)."
  []
  (doto (Executors/newSingleThreadScheduledExecutor)
    (.scheduleAtFixedRate ^Runnable rebuild! 24 24 TimeUnit/HOURS)))

(defn- stop [^ScheduledExecutorService exec] (when exec (.shutdownNow exec)))

(defstate scheduler :start (start) :stop (stop scheduler))
