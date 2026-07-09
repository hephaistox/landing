(ns landing.agora.db
  "Shared, pooled datasource for Agora tables.

  Agora reuses the existing Clever Cloud MySQL addon (same DB as the contact form).
  Connection comes from the MYSQL_ADDON_* environment variables. A bounded HikariCP
  pool — not a fresh connection per query — reuses a handful of connections, validates
  them before handing them out, and recycles/reaps them well before the addon closes
  idle ones. So a burst of queries (e.g. the admin scan) can't flood the addon's
  connection limit, and an idle gap can't leave a stale socket behind.

  The pool is deliberately small with `minimumIdle 0`: **dev, la and prod all point at
  the SAME 5-connection addon**, so we release connections when idle rather than holding
  them, and cap each instance low. Because three environments share five connections, the
  cap is per-deployment configurable via `AGORA_DB_POOL_MAX` (default 2) — set prod to 2,
  la/dev to 1 (worst case 2+1+1 = 4 ≤ 5, plus the transient contact-form connection)."
  (:require
   [mount.core           :refer [defstate]]
   [next.jdbc.connection :as connection])
  (:import (com.zaxxer.hikari HikariDataSource)))

(def ^:private pool-max
  "Max pooled connections for THIS deployment. Env-tunable because dev/la/prod share one
  5-connection addon; keep the sum across the three environments ≤ 5. Default 2."
  (or (some-> (System/getenv "AGORA_DB_POOL_MAX")
              Integer/parseInt)
      2))

(def ^:private jdbc-url
  ;; connectionTimeZone=UTC + forceConnectionTimeZoneToSet pin the session to UTC
  ;; so DATETIME values are stored and read as UTC regardless of the server/JVM
  ;; timezone. All Agora timestamps are UTC by convention.
  (format "jdbc:mysql://%s:%s/%s?connectionTimeZone=UTC&forceConnectionTimeZoneToSet=true"
          (System/getenv "MYSQL_ADDON_HOST")
          (System/getenv "MYSQL_ADDON_PORT")
          (System/getenv "MYSQL_ADDON_DB")))

(defstate
 ds
 "The pooled next.jdbc datasource (a HikariDataSource). Started by mount and closed
  on stop. `initializationFailTimeout -1` keeps startup safe even if the DB is
  briefly unreachable — connections are established lazily, on first use."
 :start (connection/->pool HikariDataSource
                           {:jdbcUrl jdbc-url
                            :username (System/getenv "MYSQL_ADDON_USER")
                            :password (System/getenv "MYSQL_ADDON_PASSWORD")
                            ;; keep the sum across dev+la+prod under the addon's 5-connection
                            ;; limit (see `pool-max` / AGORA_DB_POOL_MAX)
                            :maximumPoolSize pool-max
                            :minimumIdle 0
                            :connectionTimeout 10000 ; wait ≤10s for a free connection, else fail fast
                            :idleTimeout 30000       ; reap an idle connection after 30s → back to 0
                            :keepaliveTime 25000     ; ping a live connection every 25s so it stays valid
                            :maxLifetime 120000      ; recycle a connection after 2 min (< addon wait_timeout)
                            :initializationFailTimeout -1}) ; don't fail startup if the DB is momentarily down
 :stop (.close ^HikariDataSource ds))
