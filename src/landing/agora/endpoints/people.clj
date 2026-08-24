(ns landing.agora.endpoints.people
  "People: search existing people (accounts + external cited authors) for the author picker — a blank
  search listing the most recently attributed ones — and create a login-less external person for a
  citation whose author isn't a platform member. Creating requires a session."
  (:require
   [clojure.string                :as str]
   [landing.agora.auth            :as auth]
   [landing.agora.document.engine :as engine]
   [landing.agora.http.api        :as api]
   [landing.agora.http.throttle   :as throttle]))



(def ^:private recent-limit
  "How many people the empty search offers. Wide enough to stand as a browse of who writes here, not
  just a sample — the list is one name per line and the reader scrolls."
  100)

(defn- recent
  "The people most recently attributed a document, as `{:id :display-name :member?}`. What an empty
  search answers: a picker that opens on nothing tells a newcomer nothing, and the names worth
  offering first are the ones the corpus is actually made of."
  []
  (let [people (auth/people-index)]
    (into [] (keep people) (engine/recent-author-ids recent-limit))))

(defn- search
  [req]
  (let [q (get-in req [:parameters :query :q])]
    {:status 200
     :body (if (str/blank? q) (recent) (auth/search-people q))}))

(defn- create
  "Create a login-less external person. `:display-name` is required non-blank (coercion enforces)."
  [req]
  (if (get-in req [:session :user-id])
    {:status 200
     :body (auth/create-external-person! (get-in req [:parameters :body :display-name]))}
    {:status 401
     :body {:error "login required"}}))

(defn people-routes
  [prefix]
  [prefix
   (api/route-data :agora-people)
   [""
    {:get {:handler search
           :operationId "agora-people-search"
           :parameters {:query [:map
                                [:q {:optional true}
                                 [:maybe :string]]]}
           :responses {200
                       {:description
                        "Matching people, or the most recently attributed ones when `q` is blank"}}
           :summary "Search people; blank `q` lists the most recently attributed authors"}
     :post {:handler create
            :operationId "agora-people-create"
            :middleware [(:middleware-fn throttle/authoring-rate-limiter)]
            :parameters {:body [:map [:display-name [:string {:min 1}]]]}
            :responses {200 {:description "The created external person"}
                        401 {:description "Login required"
                             :body api/error-body}
                        429 {:description "Rate limit exceeded"}}
            :summary "Create a login-less external person"}}]])
