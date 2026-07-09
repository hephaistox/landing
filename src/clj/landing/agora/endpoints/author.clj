(ns landing.agora.endpoints.author
  "Public author profile API. `GET /agora/api/author/:id` returns the author card
  (display name, avatar, account-creation date — no email) plus their documents (latest
  minor per lineage, in the requested language) and last-activity timestamp. Read-only
  and anonymous; the author page links here from any author badge."
  (:require
   [landing.agora.auth                :as auth]
   [landing.agora.document            :as document]
   [landing.agora.endpoints.error     :as error]
   [landing.language                  :as language]
   [muuntaja.core                     :as m]
   [reitit.coercion.malli             :refer [coercion]]
   [reitit.ring.coercion              :as rcoercion]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(def ^:private author-handler
  (fn [req]
    (let [id (get-in req [:parameters :path :id])
          lang (or (get-in req [:parameters :query :lang]) language/default-lang)]
      (if-let [profile (auth/author-profile id)]
        (let [{:keys [documents last-activity]} (document/by-author id lang)]
          {:status 200
           :body (assoc profile :documents documents :last-activity last-activity)})
        {:status 404
         :body {:error "author not found"}}))))

(defn author-routes
  [prefix]
  [prefix {:coercion coercion
           :muuntaja m/instance
           :swagger {:tags #{:agora}}
           ;; `/agora/api/author/:id` overlaps the `/agora/:lang/author/:id` page shell
           ;; for the conflict detector (`api` vs the `:lang` param); reitit's matcher
           ;; prefers the literal `api` segment, so opt out of the conflict error.
           :conflicting true
           :middleware [parameters/parameters-middleware
                        muuntaja/format-negotiate-middleware
                        muuntaja/format-response-middleware
                        error/exception-middleware
                        muuntaja/format-request-middleware
                        rcoercion/coerce-request-middleware]}
   ["/:id"
    {:get {:handler author-handler
           :operationId "agora-author"
           :parameters {:path [:map [:id :string]]
                        :query [:map
                                [:lang {:optional true}
                                 [:string {:max 8}]]]}
           :summary "Public author profile: card, documents, last activity"}}]])
