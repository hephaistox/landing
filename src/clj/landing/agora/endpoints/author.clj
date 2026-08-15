(ns landing.agora.endpoints.author
  "Public author profile: a person's card plus the documents attributed to them, for the
  interactive author page. Reads the person from `auth` and their documents from `doc-storage`."
  (:require
   [landing.agora.auth                :as auth]
   [landing.agora.document.engine     :as engine]
   [muuntaja.core                     :as m]
   [reitit.ring.middleware.exception  :as exception]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(def ^:private mw
  [parameters/parameters-middleware
   muuntaja/format-negotiate-middleware
   muuntaja/format-response-middleware
   ;; an API error returns a negotiated (JSON) response, not the branded HTML 500
   exception/exception-middleware])

(defn- last-activity
  "The most recent publication time across the author's documents, or nil."
  [cards]
  (->> cards
       (keep :published-at)
       sort
       last))

(defn author-routes
  [doc-storage prefix]
  [prefix {:muuntaja m/instance
           :middleware mw}
   ["/:id"
    {:get {:handler (fn [req]
                      (let [id (get-in req [:path-params :id])
                            profile (auth/author-profile id)]
                        (if profile
                          (let [cards (engine/author-cards doc-storage id)]
                            {:status 200
                             :body
                             (assoc profile :last-activity (last-activity cards) :documents cards)})
                          {:status 404
                           :body {:error "not found"}})))
           :operationId "agora-author"
           :summary "Public author profile: card + documents"}}]])
