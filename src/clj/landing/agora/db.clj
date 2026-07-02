(ns landing.agora.db
  "Shared datasource for Agora tables.

  Agora reuses the existing Clever Cloud MySQL addon (same DB as the contact
  form). Connection comes from the MYSQL_ADDON_* environment variables. This
  namespace only exposes the datasource; KI-specific queries live elsewhere
  (see #43)."
  (:require
   [next.jdbc :as jdbc]))

(def ^:private jdbc-url
  ;; connectionTimeZone=UTC + forceConnectionTimeZoneToSet pin the session to UTC
  ;; so DATETIME values are stored and read as UTC regardless of the server/JVM
  ;; timezone. All Agora timestamps are UTC by convention.
  (format "jdbc:mysql://%s:%s/%s?connectionTimeZone=UTC&forceConnectionTimeZoneToSet=true"
          (System/getenv "MYSQL_ADDON_HOST")
          (System/getenv "MYSQL_ADDON_PORT")
          (System/getenv "MYSQL_ADDON_DB")))

(def ds
  "next.jdbc datasource for the shared MySQL DB. Building it does not open a
  connection, so it is safe at load time even without live credentials."
  (jdbc/get-datasource {:jdbcUrl  jdbc-url
                        :user     (System/getenv "MYSQL_ADDON_USER")
                        :password (System/getenv "MYSQL_ADDON_PASSWORD")}))
