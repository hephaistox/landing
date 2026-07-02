;; Agora dev helper — apply raw SQL files to the shared Clever Cloud MySQL DB.
;;
;; There is no migration runner yet (see #40), so migrations/seeds are applied
;; manually. Each file must contain a single SQL statement (line `-- comments`
;; are fine). After applying, prints the current AGORA_KI rows.
;;
;;   clojure -M scripts/agora_db.clj resources/agora/migrations/001-ki-identity.up.sql \
;;                                   resources/agora/seed/001-seed-ki.sql
(require '[next.jdbc :as jdbc])

(def ds
  (jdbc/get-datasource
   {:jdbcUrl  (format "jdbc:mysql://%s:%s/%s"
                      (System/getenv "MYSQL_ADDON_HOST")
                      (System/getenv "MYSQL_ADDON_PORT")
                      (System/getenv "MYSQL_ADDON_DB"))
    :user     (System/getenv "MYSQL_ADDON_USER")
    :password (System/getenv "MYSQL_ADDON_PASSWORD")}))

(doseq [f *command-line-args*]
  (println "applying" f)
  (jdbc/execute! ds [(slurp f)]))

(println "AGORA_KI rows:")
(doseq [row (jdbc/execute! ds ["SELECT id, name, type, major, minor, output_statement_hash, published_at FROM AGORA_KI"])]
  (prn row))
