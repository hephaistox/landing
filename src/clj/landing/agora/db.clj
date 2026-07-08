(ns landing.agora.db
  "Shared, pooled datasource for Agora tables.

  Agora reuses the existing Clever Cloud MySQL addon (same DB as the contact form).
  Connection comes from the MYSQL_ADDON_* environment variables. A bounded HikariCP
  pool — not a fresh connection per query — reuses a handful of connections, validates
  them before handing them out, and recycles/reaps them well before the addon closes
  idle ones. So a burst of queries (e.g. the admin scan) can't flood the addon's
  connection limit, and an idle gap can't leave a stale socket behind.

  The pool is deliberately small with `minimumIdle 0`: dev and prod point at the SAME
  shared addon, so we release connections when idle rather than holding them."
  (:require
   [mount.core           :refer [defstate]]
   [next.jdbc.connection :as connection])
  (:import (com.zaxxer.hikari HikariDataSource)))

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
                            ;; keep well under the shared addon's connection limit (dev + prod + the
                            ;; contact-form connection all share it)
                            :maximumPoolSize 3
                            :minimumIdle 0
                            :connectionTimeout 10000 ; wait ≤10s for a free connection, else fail fast
                            :idleTimeout 30000       ; reap an idle connection after 30s → back to 0
                            :keepaliveTime 25000     ; ping a live connection every 25s so it stays valid
                            :maxLifetime 120000      ; recycle a connection after 2 min (< addon wait_timeout)
                            :initializationFailTimeout -1}) ; don't fail startup if the DB is momentarily down
 :stop (.close ^HikariDataSource ds))
