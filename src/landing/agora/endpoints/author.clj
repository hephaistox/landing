(ns landing.agora.endpoints.author
  "Public author profile: a person's card plus the documents attributed to them, for the
  interactive author page. Reads the person from `auth` and their documents from `doc-storage`."
  (:require
   [landing.agora.auth            :as auth]
   [landing.agora.db.document     :as db-doc]
   [landing.agora.document.engine :as engine]
   [landing.agora.http.api        :as api]))



(defn- admin?
  "Whether the caller is the platform owner. The public profile carries one extra field for them."
  [req]
  (boolean (some-> (get-in req [:session :user-id])
                   auth/get-user
                   :admin)))

(defn- may-see-drafts?
  "Whether this caller may be shown `id`'s unpublished work. One shared rule with the by-id read
  (`auth/sees-drafts-of?`), so a card offered here always opens."
  [req id]
  (auth/sees-drafts-of? (get-in req [:session :user-id]) id))

(defn- last-activity
  "The most recent publication time across the author's documents, or nil."
  [cards]
  (->> cards
       (keep :published-at)
       sort
       last))

(defn author-routes
  [doc-storage prefix]
  [prefix
   (api/route-data :agora-author)
   ["/:id"
    {:get
     {:handler
      (fn [req]
        (let [id (get-in req [:path-params :id])
              profile (auth/author-profile id)]
          (if profile
            (let [cards (engine/author-cards doc-storage id (may-see-drafts? req id))]
              {:status 200
               :body (cond-> (assoc profile :last-activity (last-activity cards) :documents cards)
                       ;; administrators only: what removing this person would
                       ;; re-sign. It counts drafts, so it says how much unpublished
                       ;; work sits behind the name — nobody else's business
                       (admin? req) (assoc :attributed-count (db-doc/attributed-count id)))})
            {:status 404
             :body {:error "not found"}})))
      :operationId "agora-author"
      :parameters {:path [:map [:id :string]]}
      :responses
      {200
       {:description
        "Author profile: card + documents + last activity (+ the attributed count, for an administrator)"}
       404 {:description "No such author"
            :body api/error-body}}
      :summary "Public author profile: card + documents"}}]])
