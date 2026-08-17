(ns landing.agora.endpoints.publication
  "Publications: open one, list them (the caller's own or every one), fetch by id, rename, publish
  (close), and list a publication's documents. All mutations require a session and are owner-only."
  (:require
   [landing.agora.auth                :as auth]
   [landing.agora.document.cached-db  :as cached-db]
   [landing.agora.document.engine     :as engine]
   [landing.agora.publication         :as publication]
   [muuntaja.core                     :as m]
   [reitit.coercion.malli             :refer [coercion]]
   [reitit.ring.coercion              :as rcoercion]
   [reitit.ring.middleware.exception  :as exception]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(def ^:private mw
  [parameters/parameters-middleware
   muuntaja/format-negotiate-middleware
   muuntaja/format-response-middleware
   ;; catch exceptions here so an API error returns a negotiated (JSON) response, not the branded
   ;; HTML 500 the outer web handler would serve
   exception/exception-middleware
   muuntaja/format-request-middleware
   rcoercion/coerce-request-middleware])

(def ^:private id-path "The publication cid in the path." [:map [:id :string]])

(def ^:private title-body
  "The optional title a create/rename sets (a blank title is auto-named `publication<N>`)."
  [:map
   [:title {:optional true}
    [:maybe :string]]])

(def ^:private error-body
  "The shape of every error response: `{:error \"...\"}` (some carry extra keys, e.g. publish's
  `:documents`)."
  [:map [:error :string]])

(defn- uid [req] (get-in req [:session :user-id]))

(defn- create
  "Open a publication owned by the caller, titled by the request body."
  [req]
  (if-let [id (uid req)]
    {:status 200
     :body
     (publication/create! id (:display-name (auth/get-user id)) (get-in req [:body-params :title]))}
    {:status 401
     :body {:error "login required"}}))

(defn- list-visible
  "Publications for the index, optionally filtered by `?q=`. `?scope=all` lists every publication;
  any other value (default) lists the caller's own."
  [req]
  (if-let [id (uid req)]
    (let [scope (if (= "all" (get-in req [:query-params "scope"])) :all :mine)]
      {:status 200
       :body (publication/list-visible id scope (get-in req [:query-params "q"]))})
    {:status 401
     :body {:error "login required"}}))

(defn- fetch
  [req]
  (if-let [p (publication/fetch (get-in req [:path-params :id]))]
    {:status 200
     :body p}
    {:status 404
     :body {:error "not found"}}))

(defn- documents
  "The documents a publication gathers (its drafts), as browse cards."
  [doc-storage req]
  {:status 200
   :body (engine/publication-cards doc-storage (get-in req [:path-params :id]))})

(defn- graph
  "The publication's 1-hop draft graph — nodes + edges — for the graph view."
  [doc-storage req]
  {:status 200
   :body (engine/publication-subgraph doc-storage (get-in req [:path-params :id]))})

(defn- rename
  "Rename a publication (owner-only). Body `{:title}`."
  [req]
  (if-let [id (uid req)]
    (if-let [p (publication/rename! id
                                    (get-in req [:path-params :id])
                                    (get-in req [:body-params :title]))]
      {:status 200
       :body p}
      {:status 404
       :body {:error "not found"}})
    {:status 401
     :body {:error "login required"}}))

(defn- publish
  "Publish (close) a publication (owner-only): its gathered drafts go public, the publication closes.
  A document must be error-free to be published, so this **refuses (422)** when any gathered draft
  still has `:errors`, returning the offending documents (cards, with their errors) for the client to
  list. Clears the read caches on success."
  [doc-storage req]
  (if-let [id (uid req)]
    (let [cid (get-in req [:path-params :id])
          in-error (filterv (comp seq :errors) (engine/publication-cards doc-storage cid))]
      (if (seq in-error)
        {:status 422
         :body {:error "documents in error"
                :documents in-error}}
        (if-let [p (publication/publish! id cid)]
          (do (cached-db/clear!)
              {:status 200
               :body p})
          {:status 404
           :body {:error "not found or already closed"}})))
    {:status 401
     :body {:error "login required"}}))

(defn- delete
  "Delete an open publication and the drafts it gathers (owner-only). Clears the read caches."
  [req]
  (if-let [id (uid req)]
    (if (publication/delete! id (get-in req [:path-params :id]))
      (do (cached-db/clear!)
          {:status 200
           :body {:ok true}})
      {:status 404
       :body {:error "not found or already closed"}})
    {:status 401
     :body {:error "login required"}}))

(defn publication-routes
  [doc-storage prefix]
  [prefix {:coercion coercion
           :muuntaja m/instance
           :swagger {:tags #{:agora-publication}}
           :responses {500 {:description "Unexpected server error"}}
           :middleware mw}
   [""
    {:get {:handler list-visible
           :operationId "agora-publications-search"
           :parameters {:query [:map
                                [:scope {:optional true}
                                 [:maybe :string]]
                                [:q {:optional true}
                                 [:maybe :string]]]}
           :responses {200 {:description "Visible publications"}
                       401 {:description "Login required"
                            :body error-body}}
           :summary "List publications (scope=all for every publication, else the caller's own)"}
     :post {:handler create
            :operationId "agora-publication-create"
            :parameters {:body title-body}
            :responses {200 {:description "The opened publication"}
                        401 {:description "Login required"
                             :body error-body}}
            :summary "Open a publication"}}]
   ["/:id"
    {:get {:handler fetch
           :operationId "agora-publication"
           :parameters {:path id-path}
           :responses {200 {:description "The publication"}
                       404 {:description "No such publication"
                            :body error-body}}
           :summary "Fetch a publication by id"}
     :put {:handler rename
           :operationId "agora-publication-rename"
           :parameters {:path id-path
                        :body title-body}
           :responses {200 {:description "The renamed publication"}
                       401 {:description "Login required"
                            :body error-body}
                       404 {:description "Not the owner, or no such publication"
                            :body error-body}}
           :summary "Rename a publication (owner-only)"}
     :delete {:handler delete
              :operationId "agora-publication-delete"
              :parameters {:path id-path}
              :responses {200 {:description "Deleted"}
                          401 {:description "Login required"
                               :body error-body}
                          404 {:description "Not the owner, or already closed"
                               :body error-body}}
              :summary "Delete an open publication and its drafts (owner-only)"}}]
   ["/:id/publish"
    {:post {:handler (partial publish doc-storage)
            :operationId "agora-publication-publish"
            :parameters {:path id-path}
            :responses {200 {:description "Published (closed); its drafts are now public"}
                        401 {:description "Login required"
                             :body error-body}
                        404 {:description "Not the owner, or already closed"
                             :body error-body}
                        422 {:description
                             "A gathered draft still has errors — the offending docs are returned"
                             :body [:map [:error :string] [:documents [:sequential :any]]]}}
            :summary "Publish (close) a publication — its drafts go public (owner-only)"}}]
   ["/:id/documents"
    {:get {:handler (partial documents doc-storage)
           :operationId "agora-publication-documents"
           :parameters {:path id-path}
           :responses {200 {:description "The publication's drafts, as browse cards"}}
           :summary "The publication's modified documents (drafts)"}}]
   ["/:id/graph"
    {:get {:handler (partial graph doc-storage)
           :operationId "agora-publication-graph"
           :parameters {:path id-path}
           :responses {200 {:description "The 1-hop draft graph (nodes + edges)"}}
           :summary "The publication's 1-hop draft graph (nodes + edges) for the graph view"}}]])
