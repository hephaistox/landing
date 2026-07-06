(ns landing.agora.article
  "Read access to articles. The body is stored inline on the article row."
  (:require
   [landing.agora.db     :as db]
   [landing.agora.util   :as util]
   [next.jdbc            :as jdbc]
   [next.jdbc.result-set :as rs]))

(defn fetch-article
  "Fetch the article identified by `id` (title, body, timestamp), or nil if no such
  article. :published-at is returned as an ISO-8601 UTC string."
  [id]
  (when-let [row (jdbc/execute-one!
                  db/ds
                  ["SELECT id, title, body, published_at FROM AGORA_ARTICLE WHERE id = ?" id]
                  {:builder-fn rs/as-unqualified-kebab-maps})]
    (update row :published-at util/->utc-iso)))
