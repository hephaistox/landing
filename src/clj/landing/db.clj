(ns landing.db
  "Connect to local database"
  (:require
   [next.jdbc :as jdbc]))

(def db-name (System/getenv "MYSQL_ADDON_DB"))
(def db-host (System/getenv "MYSQL_ADDON_HOST"))
(def db-pwd (System/getenv "MYSQL_ADDON_PASSWORD"))
(def db-port (System/getenv "MYSQL_ADDON_PORT"))
(def db-user (System/getenv "MYSQL_ADDON_USER"))

(def url (format "jdbc:mysql://%s-mysql.services.clever-cloud.com:%s/%s" db-name db-port db-name))

(def ds
  (jdbc/get-datasource {:jdbcUrl url
                        :user db-user
                        :password db-pwd}))

(defn query
  [company name firstname mail phone]
  [(str
    "INSERT INTO `CONTACTS`(`CREATION`,`COMPANY`, `NAME`, `FIRSTNAME`, `MAIL`, `PHONE`) VALUES (now(),'"
    company
    "','"
    name
    "','"
    firstname
    "','"
    mail
    "','"
    phone
    "')")])

(defn execute-query
  [ds query]
  (with-open [connection (jdbc/get-connection ds)] (jdbc/execute! connection query)))

(comment
  (->> (query "SASU CAUMOND" "CAUMOND" "Anthony" "caumond@gmail.com" "0665250279")
       (execute-query ds))
  ;
)
