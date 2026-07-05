;; Agora dev helper — apply raw SQL files to the shared Clever Cloud MySQL DB.
;;
;; There is no migration runner yet (see #40), so migrations/seeds are applied
;; manually. Files may contain multiple `;`-separated statements (allowMultiQueries
;; is enabled below; `;` inside quoted string literals is handled by MySQL, not us).
;; After applying, prints the current AGORA_NODE rows.
;;
;;   clojure -M scripts/agora_db.clj resources/agora/migrations/001-schema.up.sql \
;;                                   resources/agora/seed/001-seed.sql
(require '[next.jdbc :as jdbc])

(def ds
  (jdbc/get-datasource {:jdbcUrl (format "jdbc:mysql://%s:%s/%s?allowMultiQueries=true"
                                         (System/getenv "MYSQL_ADDON_HOST")
                                         (System/getenv "MYSQL_ADDON_PORT")
                                         (System/getenv "MYSQL_ADDON_DB"))
                        :user (System/getenv "MYSQL_ADDON_USER")
                        :password (System/getenv "MYSQL_ADDON_PASSWORD")}))

(doseq [f *command-line-args*]
  (println "applying" f)
  (jdbc/execute! ds [(slurp f)]))

(println "AGORA_NODE rows:")
(doseq
  [row
   (jdbc/execute!
    ds
    ["SELECT id, name, type, major, minor, output_statement_hash, published_at FROM AGORA_NODE"])]
  (prn row))
