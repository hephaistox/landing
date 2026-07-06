(ns landing.agora.endpoints.article
  "HTTP routes for reading articles: list (discover), by permanent identity
  (name + major → latest minor), and by concrete id. Read-only and anonymous, mirroring
  landing.agora.endpoints.ki. Article authoring is a later slice."
  (:require
   [landing.agora.article             :as article]
   [landing.agora.endpoints.error     :as error]
   [landing.language                  :as language]
   [muuntaja.core                     :as m]
   [reitit.coercion.malli             :refer [coercion]]
   [reitit.ring.coercion              :as rcoercion]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(def ^:private mw
  [parameters/parameters-middleware
   muuntaja/format-negotiate-middleware
   muuntaja/format-response-middleware
   error/exception-middleware
   muuntaja/format-request-middleware
   rcoercion/coerce-request-middleware])

(def ^:private lang-schema
  [:string {:max 8}])
(def ^:private name-schema
  [:string {:min 1
            :max 200}])
(def ^:private title-schema
  [:string {:min 1
            :max 200}])
(def ^:private body-schema
  [:string {:min 1
            :max 50000}])

(def list-articles-handler
  "The article discover list, scoped to the content language `?lang=` (default fr)."
  (fn [req]
    (let [lang (or (get-in req [:parameters :query :lang]) language/default-lang)]
      {:status 200
       :body (article/list-articles lang)})))

(def article-by-major-handler
  "Latest-minor article of a (name, major) lineage in `?lang=` — the permanent public
  identity — or 404."
  (fn [req]
    (let [{art-name :name
           art-major :major}
          (get-in req [:parameters :path])
          lang (or (get-in req [:parameters :query :lang]) language/default-lang)]
      (if-let [a (article/fetch-article-by-major art-name art-major lang)]
        {:status 200
         :body a}
        {:status 404
         :body {:error "Article not found"
                :name art-name
                :major art-major}}))))

(def article-handler
  "Return the article identified by the :id path param, or 404 if none."
  (fn [req]
    (let [id (get-in req [:parameters :path :id])]
      (if-let [a (article/fetch-article id)]
        {:status 200
         :body a}
        {:status 404
         :body {:error "Article not found"
                :id id}}))))

(def create-article-handler
  "Create a new article (major 1, minor 0) owned by the logged-in user. Cited KIs are
  parsed from the body. 201 with the article, or 401 if not logged in."
  (fn [req]
    (if-let [uid (get-in req [:session :user-id])]
      {:status 201
       :body (article/create-article uid (get-in req [:parameters :body]))}
      {:status 401
       :body {:error "login required"}})))

(defn article-collection-route
  "The article collection: GET lists latest-minor articles (?lang=)."
  [prefix]
  [prefix
   {:coercion coercion
    :muuntaja m/instance
    :swagger {:tags #{:agora}}
    :middleware mw
    :get {:handler list-articles-handler
          :operationId "agora-list-articles"
          :parameters {:query [:map
                               [:lang {:optional true}
                                lang-schema]]}
          :summary "List articles (discover, scoped to ?lang=)"}
    :post {:handler create-article-handler
           :operationId "agora-create-article"
           :parameters {:body [:map
                               [:name name-schema]
                               [:title title-schema]
                               [:lang {:optional true}
                                lang-schema]
                               [:body body-schema]]}
           :summary "Create a new article (major 1, minor 0)"}}])

(defn article-by-major-route
  "Public permanent identity: GET /api/article/by/:name/:major → latest minor."
  [prefix]
  [prefix {;; :conflicting — overlaps `/agora/api/article/:id`; reitit prefers the literal `by`.
           :conflicting true
           :get {:coercion coercion
                 :handler article-by-major-handler
                 :muuntaja m/instance
                 :operationId "agora-article-by-major"
                 :parameters {:path [:map [:name name-schema] [:major :int]]
                              :query [:map
                                      [:lang {:optional true}
                                       lang-schema]]}
                 :summary "Fetch an article's latest minor by (name, major), in ?lang="
                 :swagger {:tags #{:agora}}
                 :middleware mw}}])

(defn article-route
  [prefix]
  [prefix {;; :conflicting — `/agora/api/article/:id` overlaps the app-shell
           ;; `/agora/:lang/article/:id`; reitit's matcher prefers the literal `api`.
           :conflicting true
           :get {:coercion coercion
                 :handler article-handler
                 :muuntaja m/instance
                 :operationId "agora-article-by-id"
                 :parameters {:path [:map [:id :string]]}
                 :summary "Fetch a single article by id"
                 :swagger {:tags #{:agora}}
                 :middleware mw}}])
