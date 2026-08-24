(ns landing.agora.endpoints.admin
  "Maintenance and consistency routes over the document store: list lineages, list reference issues,
  drop or compact a lineage, recompute the successor index, remove a person. Every route is restricted to the
  platform owner (`admin-only`): 401 when anonymous, 403 when logged in but not on the admin
  allowlist."
  (:require
   [landing.agora.admin               :as admin]
   [landing.agora.auth                :as auth]
   [landing.agora.person.erasure      :as erasure]
   [muuntaja.core                     :as m]
   [reitit.coercion.malli             :refer [coercion]]
   [reitit.ring.coercion              :as rcoercion]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(def ^:private mw
  [parameters/parameters-middleware
   muuntaja/format-negotiate-middleware
   muuntaja/format-response-middleware
   muuntaja/format-request-middleware
   rcoercion/coerce-request-middleware])

(def ^:private lineage-body
  "The (type, name, lang, major) lineage a drop/compact targets."
  [:map [:type :string] [:name :string] [:lang :string] [:major :int]])

(def ^:private error-body
  "The shape of every error response: `{:error \"...\"}`."
  [:map [:error :string]])

(defn- admin-only
  "Run `(f req)` only for an authenticated administrator; 401 anonymous, 403 non-admin."
  [req f]
  (if-let [uid (get-in req [:session :user-id])]
    (if (:admin (auth/get-user uid))
      (f req)
      {:status 403
       :body {:error "admin only"}})
    {:status 401
     :body {:error "login required"}}))

(defn- tnrs
  [req]
  (admin-only req
              (fn [_]
                {:status 200
                 :body (admin/all-tnrs)})))

(defn- issues
  [req]
  (admin-only req
              (fn [_]
                {:status 200
                 :body (admin/consistency-issues)})))

(defn- drop-tnr
  [req]
  (admin-only req
              (fn [{{:keys [type lang major]
                     doc-name :name}
                    :body-params}]
                {:status 200
                 :body {:removed (admin/delete-tnr! type doc-name lang major)}})))

(defn- compact-tnr
  [req]
  (admin-only req
              (fn [{{:keys [type lang major]
                     doc-name :name}
                    :body-params}]
                {:status 200
                 :body {:removed (admin/compact-tnr! type doc-name lang major)}})))

(defn- rebuild
  [req]
  (admin-only req
              (fn [_]
                {:status 200
                 :body (admin/reconcile!)})))

(defn- delete-person
  "Remove a person: whatever they are attributed with becomes the anonymous person's, then the account
  row goes. Always possible — the count of what will change byline is served on their profile, so the
  confirmation names it beforehand. That is a decision to put in front of the administrator, not a
  refusal to hand them.

  Nothing is destroyed (see `erasure/retire!`): documents and drafts stay where they are, under a
  byline that names nobody."
  [req]
  (admin-only req
              (fn [{{:keys [id]} :path-params}]
                (cond
                  (auth/reserved? id) {:status 403
                                       :body {:error "reserved person"}}
                  (nil? (auth/author-profile id)) {:status 404
                                                   :body {:error "not found"}}
                  :else {:status 200
                         :body (erasure/retire! id)}))))

(defn admin-routes
  [prefix]
  ;; every admin route is owner-only, so 401/403 (and 500) are declared once here and merge into
  ;; each operation; the routes add only their own 200
  [prefix {:coercion coercion
           :muuntaja m/instance
           ;; kept out of the public OpenAPI document: the owner guard is what protects these, but
           ;; there is no reason to publish a maintenance surface — including the destructive ones —
           ;; to every passing scanner. The routes themselves are unchanged.
           :no-doc true
           :swagger {:tags #{:agora-admin}}
           :responses {401 {:description "Login required"
                            :body error-body}
                       403 {:description "Admin only"
                            :body error-body}
                       500 {:description "Unexpected server error"}}
           :middleware mw}
   ["/person/:id"
    {:delete {:handler delete-person
              :operationId "agora-admin-delete-person"
              :parameters {:path [:map [:id :string]]}
              :responses {200 {:description
                               "How many documents changed byline, and whether the row went"}
                          403 {:description "A seeded platform person may not be removed"
                               :body error-body}
                          404 {:description "No such person"
                               :body error-body}}
              :summary "Remove a person, anonymising what they are attributed with"}}]
   ["/tnrs"
    {:get {:handler tnrs
           :operationId "agora-admin-tnrs"
           :responses {200 {:description "Document lineages (TNRs) with version counts"}}
           :summary "List document lineages (TNRs) with version counts"}}]
   ["/issues"
    {:get {:handler issues
           :operationId "agora-admin-issues"
           :responses {200 {:description "Versions with reference problems"}}
           :summary "Versions with reference problems (dangling / self / successor-cache drift)"}}]
   ["/drop-tnr"
    {:post {:handler drop-tnr
            :operationId "agora-admin-drop-tnr"
            :parameters {:body lineage-body}
            :responses {200 {:description "How many versions were removed"}}
            :summary "Drop a whole (type, name, lang, major) lineage"}}]
   ["/compact-tnr"
    {:post {:handler compact-tnr
            :operationId "agora-admin-compact-tnr"
            :parameters {:body lineage-body}
            :responses {200 {:description "How many versions were removed"}}
            :summary "Keep only the latest minor of a lineage"}}]
   ["/rebuild"
    {:post {:handler rebuild
            :operationId "agora-admin-rebuild"
            :responses {200 {:description "Successor index recomputed"}}
            :summary "Recompute the successor index"}}]])
