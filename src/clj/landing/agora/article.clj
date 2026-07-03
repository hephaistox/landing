(ns landing.agora.article
  "Read access to articles.

  An article row stores only the hash of its body; this resolves that hash to the
  actual text via the blob store, returning a single clean map."
  (:require
   [landing.agora.blob   :as blob]
   [landing.agora.db     :as db]
   [landing.agora.util   :as util]
   [next.jdbc            :as jdbc]
   [next.jdbc.result-set :as rs]))

(defn fetch-article
  "Fetch the article identified by `id`, resolving its body text from the blob
  store. Returns a map of the article fields (unqualified, kebab-case) with an
  extra :body key holding the resolved text, or nil if no such article.
  :published-at is returned as an ISO-8601 UTC string."
  [id]
  (when-let [row (jdbc/execute-one!
                  db/ds
                  ["SELECT id, title, body_hash, published_at FROM AGORA_ARTICLE WHERE id = ?" id]
                  {:builder-fn rs/as-unqualified-kebab-maps})]
    (-> row
        (assoc :body (blob/read-blob (:body-hash row)))
        (update :published-at util/->utc-iso))))
