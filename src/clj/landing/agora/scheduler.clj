(ns landing.agora.scheduler
  "Daily in-process reconcile of the Agora derived caches — `computed.:pins` and the successor index
  are diffed against the immutable `content` and only discrepancies are repaired (see
  `admin/reconcile!`). Needs no external cron; started/stopped with the app via Mount."
  (:require
   [auto-core.log       :as core-log]
   [landing.agora.admin :as admin]
   [mount.core          :refer [defstate]])
  (:import (java.time Duration ZoneOffset ZonedDateTime)
           (java.time.temporal ChronoUnit)
           (java.util.concurrent Executors ScheduledExecutorService TimeUnit)))

(def ^:private run-hour-utc "Daily reconcile fires at this UTC hour." 4)

(defn- rebuild!
  []
  (try (core-log/info "Agora: daily reconcile of derived caches")
       (admin/reconcile!)
       (catch Throwable e (core-log/error-exception e "Agora: derived-cache reconcile failed"))))

(defn- millis-until-next-run
  "Milliseconds from now until the next `run-hour-utc`:00 UTC (today if still ahead,
  otherwise tomorrow)."
  []
  (let [now (ZonedDateTime/now ZoneOffset/UTC)
        next (-> now
                 (.withHour run-hour-utc)
                 (.truncatedTo ChronoUnit/HOURS))
        next (if (.isAfter next now) next (.plusDays next 1))]
    (.toMillis (Duration/between now next))))

(defn- start
  "Schedule the rebuild at `run-hour-utc`:00 UTC every day (first run at the next such
  time — the index is already fresh at startup, so nothing is missed before then)."
  []
  (doto (Executors/newSingleThreadScheduledExecutor)
    (.scheduleAtFixedRate ^Runnable rebuild!
                          (millis-until-next-run)
                          (.toMillis (Duration/ofDays 1))
                          TimeUnit/MILLISECONDS)))

(defn- stop [^ScheduledExecutorService exec] (when exec (.shutdownNow exec)))

(defstate scheduler :start (start) :stop (stop scheduler))
