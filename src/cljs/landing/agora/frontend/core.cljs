(ns landing.agora.frontend.core
  "Agora frontend entry point — a small single-page app over the Agora routes.

  Pushy (HTML5 history) intercepts in-app links and programmatic navigation, so
  moving between KIs/articles and landing on a freshly edited version updates the
  view via re-frame without a full page reload. The backend serves the SPA shell
  for every `/agora/<lang>/...` path, so direct URLs and refresh keep working.

  Fetched documents are held in a bounded (≤1000) cache keyed by id, and the
  displayed view only swaps once data is available. The backend pins inputs and
  re-pins successors, so there is no client-side version resolution — neighbour
  refs are used as returned."
  (:require
   [cljs.pprint                             :refer [pprint]]
   [landing.agora.document.identity         :as di]
   [landing.agora.frontend.admin            :as admin]
   [landing.agora.frontend.article-page     :as article-page]
   [landing.agora.frontend.auth             :as auth]
   [landing.agora.frontend.author-page      :as author-page]
   [landing.agora.frontend.chrome           :as chrome]
   [landing.agora.frontend.document-page    :as document-page]
   [landing.agora.frontend.edit             :as edit]
   [landing.agora.frontend.find-page        :as find-page]
   [landing.agora.frontend.graph            :as graph]
   [landing.agora.frontend.i18n             :as i18n]
   [landing.agora.frontend.ki-page          :as ki-page]
   [landing.agora.frontend.landing          :as landing]
   [landing.agora.frontend.loading          :as loading]
   [landing.agora.frontend.modal            :as modal]
   [landing.agora.frontend.preferences-page :as preferences-page]
   [landing.agora.frontend.publications     :as publications]
   [landing.language                        :as language]
   [pushy.core                              :as pushy]
   [re-frame.core                           :as rf]
   [reagent.dom                             :as rdom]
   [superstructor.re-frame.fetch-fx]))

(goog-define ENV "dev")

(def ^:private api-path
  {:ki "/agora/api/documents/ki/"
   :article "/agora/api/documents/article/"})

(def ^:private cache-ttl-ms
  "How long a cached resource stays fresh before it is refetched. Kept short: documents are
  edited (each edit is a new minor), and the cache holds both the focal view and neighbour
  previews — a long TTL means a stale permalink/preview until a hard refresh. A local edit
  busts its whole lineage immediately (see `cache-evict-lineage`); this TTL only bounds how
  long an edit made *elsewhere* (another tab/user) can look stale."
  (* 30 1000))

(defn path->route
  "Parse an Agora SPA path into {:kind …}, or nil when it is not an Agora route
  (Pushy then lets the browser handle the click normally). A KI is addressed two
  ways: the public permalink `/ki/<name>/<major>` (latest minor) and the app URL
  `/ki/<id>` (a specific version)."
  [path]
  (cond
    (re-find #"^/agora/([a-z]{2})/?(?:[?#].*)?$" path) {:kind :home
                                                        :lang (second (re-find #"^/agora/([a-z]{2})"
                                                                               path))}
    (re-find #"^/agora/([a-z]{2})/discover/?(?:[?#].*)?$" path)
    {:kind :discover
     :lang (second (re-find #"^/agora/([a-z]{2})/discover" path))}
    (re-find #"^/agora/([a-z]{2})/articles/?(?:[?#].*)?$" path)
    {:kind :articles
     :lang (second (re-find #"^/agora/([a-z]{2})/articles" path))}
    (re-find #"^/agora/([a-z]{2})/authors/?(?:[?#].*)?$" path)
    {:kind :authors
     :lang (second (re-find #"^/agora/([a-z]{2})/authors" path))}
    (re-find #"^/agora/([a-z]{2})/sources/?(?:[?#].*)?$" path)
    {:kind :sources
     :lang (second (re-find #"^/agora/([a-z]{2})/sources" path))}
    (re-find #"^/agora/([a-z]{2})/article/new/?(?:[?#].*)?$" path)
    {:kind :article-new
     :lang (second (re-find #"^/agora/([a-z]{2})/" path))}
    (re-find #"^/agora/([a-z]{2})/preferences/?(?:[?#].*)?$" path)
    {:kind :preferences
     :lang (second (re-find #"^/agora/([a-z]{2})/preferences" path))}
    (re-find #"^/agora/([a-z]{2})/admin/?(?:[?#].*)?$" path)
    {:kind :admin
     :lang (second (re-find #"^/agora/([a-z]{2})/admin" path))}
    (re-find #"^/agora/([a-z]{2})/publications/?(?:[?#].*)?$" path)
    {:kind :publications
     :lang (second (re-find #"^/agora/([a-z]{2})/publications" path))}
    (re-find #"^/agora/([a-z]{2})/publication/([^/?#]+)/graph/?(?:[?#].*)?$" path)
    (let [[_ lang id] (re-find #"^/agora/([a-z]{2})/publication/([^/?#]+)/graph" path)]
      {:kind :publication-graph
       :lang lang
       :id (js/decodeURIComponent id)})
    (re-find #"^/agora/([a-z]{2})/publication/([^/?#]+)/?(?:[?#].*)?$" path)
    (let [[_ lang id] (re-find #"^/agora/([a-z]{2})/publication/([^/?#]+)" path)]
      {:kind :publication
       :lang lang
       :id (js/decodeURIComponent id)})
    (re-find #"^/agora/([a-z]{2})/author/([^/?#]+)/?(?:[?#].*)?$" path)
    (let [[_ lang id] (re-find #"^/agora/([a-z]{2})/author/([^/?#]+)" path)]
      {:kind :author
       :lang lang
       :id (js/decodeURIComponent id)})
    (re-find #"^/agora/([a-z]{2})/new/?(?:[?#].*)?$" path)
    {:kind :new
     :lang (second (re-find #"^/agora/([a-z]{2})/" path))}
    (re-find #"^/agora/([a-z]{2})/ki/([^/?#]+)/([0-9]+)/?(?:[?#].*)?$" path)
    (let [[_ lang n mj] (re-find #"^/agora/([a-z]{2})/ki/([^/?#]+)/([0-9]+)" path)]
      {:kind :ki-public
       :lang lang
       :name (js/decodeURIComponent n)
       :major (js/parseInt mj)})
    (re-find #"^/agora/([a-z]{2})/ki/([^/?#]+)/?(?:[?#].*)?$" path)
    (let [[_ lang id] (re-find #"^/agora/([a-z]{2})/ki/([^/?#]+)" path)]
      {:kind :ki
       :lang lang
       :id (js/decodeURIComponent id)})
    (re-find #"^/agora/([a-z]{2})/article/([^/?#]+)/([0-9]+)/?(?:[?#].*)?$" path)
    (let [[_ lang n mj] (re-find #"^/agora/([a-z]{2})/article/([^/?#]+)/([0-9]+)" path)]
      {:kind :article-public
       :lang lang
       :name (js/decodeURIComponent n)
       :major (js/parseInt mj)})
    (re-find #"^/agora/([a-z]{2})/article/([^/?#]+)" path)
    (let [[_ lang id] (re-find #"^/agora/([a-z]{2})/article/([^/?#]+)" path)]
      {:kind :article
       :lang lang
       :id (js/decodeURIComponent id)})
    :else nil))

(defonce ^:private history (atom nil))

;; ---------------------------------------------------------------------------
;; Fetched-document cache (bounded LRU)
;;
;; :cache {id {:data … :at ms}} — every document held once, under its id, capped so
;; the SPA holds only recently-shown documents. :tnlr {[type lang cid major] id} indexes
;; a lineage identity to its current id, so the permalink and living-citation loads (which
;; hold an identity, not an id) resolve into the same store. Inputs are pinned and successors
;; re-pinned server-side, so there is NO client-side latest-minor resolution — neighbour refs
;; are used exactly as the backend returned them.
;; ---------------------------------------------------------------------------

(def ^:private cache-max-entries 1000)

(defn- kw-kind
  "Keyword a document's epistemic `:kind` at the wire boundary — JSON delivers it as a string, the
  domain uses keywords (`:source`, `:inference`)."
  [doc]
  (cond-> doc
    (:kind doc) (update :kind keyword)))

(defn- cache-put
  "Store `data` under `k` with a timestamp, evicting the least-recently-fetched
  entries beyond the cap."
  [db k data]
  (let [entries (assoc (:cache db)
                       k
                       {:data data
                        :at (js/Date.now)})]
    (assoc db
           :cache
           (if (> (count entries) cache-max-entries)
             (into {} (take-last cache-max-entries (sort-by (comp :at val) entries)))
             entries))))

(defn- tnlr-key
  "Normalized identity key for the `:tnlr` index. The same lineage maps to one key
  whatever shape the caller holds it in — keyword or string type/lang, a bare cid or
  a `cid~slug` name, an int or a numeric-string major."
  [type lang doc-name major]
  [(keyword type)
   (keyword lang)
   (di/cid-of doc-name)
   (if (number? major) major (js/parseInt major))])

(defn- cache-put-doc
  "Ingest a fetched document into the one id-keyed store and index its identity.
  Every loader — by id, by permalink, by citation — funnels through here, so a
  document is held once (under its id) and the `:tnlr` index resolves a lineage
  identity to that id."
  [db doc]
  (let [doc (kw-kind doc)]
    (-> db
        (cache-put (:id doc) doc)
        (assoc-in [:tnlr (tnlr-key (:type doc) (:lang doc) (:name doc) (:major doc))] (:id doc)))))

(defn- doc-by-id
  "The cached document held under `id` (nil if absent)."
  [db id]
  (get-in db [:cache id :data]))

(defn- doc-by-tnlr
  "The cached document for a lineage identity, resolved through the `:tnlr` index."
  [db type lang doc-name major]
  (doc-by-id db (get-in db [:tnlr (tnlr-key type lang doc-name major)])))

(defn- cache-evict-lineage
  "Drop every cached entry belonging to the same lineage as `doc` — same (type, name,
  major), any minor / id / language. Used after a local write so the focal view, the
  permalink, the neighbour previews and the old-minor id all refetch fresh instead of
  serving the pre-edit version until the TTL lapses."
  [db {:keys [type name major]}]
  (let [t (keyword type)
        cid (di/cid-of name)
        mj (if (number? major) major (js/parseInt major))]
    (-> db
        (update :cache
                (fn [cache]
                  (into {}
                        (remove (fn [[_ {d :data}]]
                                  (and (= (:type d) type) (= (:name d) name) (= (:major d) major))))
                        cache)))
        (update
         :tnlr
         (fn [idx]
           (into {} (remove (fn [[[kt _ kn kmj] _]] (and (= kt t) (= kn cid) (= kmj mj)))) idx))))))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defn- route-changed-fetch
  "Cache-or-fetch a :ki/:article route."
  [db kind id]
  (let [entry (get-in db [:cache id])
        fresh? (and entry (< (- (js/Date.now) (:at entry)) cache-ttl-ms))]
    (if fresh?
      ;; Fresh cache hit — swap instantly, no request.
      {:db (assoc db
                  :view {:kind kind
                         :data (:data entry)}
                  :loading? false
                  :error nil)}
      ;; Miss/stale — fetch, but keep the current :view on screen until it lands.
      {:db (assoc db :loading? true :error nil)
       ;; scope to the active publication so its draft successors overlay the authoring view
       :fetch {:method :get
               :url (str (api-path kind) id (publications/active-query db))
               :headers {"Accept" "application/json"}
               :response-content-types {#"application/json" :json}
               :on-success [::fetch-ok kind id]
               :on-failure [::fetch-failed]}})))

(defn- route-changed-fetch-public
  "Cache-or-fetch the public permalink /agora/{lang}/ki/{name}/{major}. The `lang`
  comes from the URL — it is the content language to display for this KI, which is
  independent of the interface-language preference (the permalink overrides it)."
  [db ki-name ki-major lang]
  (let [entry (get-in db [:cache (get-in db [:tnlr (tnlr-key :ki lang ki-name ki-major)])])
        fresh? (and entry (< (- (js/Date.now) (:at entry)) cache-ttl-ms))]
    (if fresh?
      {:db (assoc db
                  :view {:kind :ki-public
                         :data (:data entry)}
                  :loading? false
                  :error nil)}
      {:db (assoc db :loading? true :error nil)
       :fetch
       {:method :get
        :url (str "/agora/api/documents/ki/" (js/encodeURIComponent ki-name) "/" lang "/" ki-major)
        :headers {"Accept" "application/json"}
        :response-content-types {#"application/json" :json}
        :on-success [::fetch-public-ok :ki-public]
        :on-failure [::fetch-failed]}})))

;; Both permalink loaders (KI and article) land here — the returned document carries
;; its `:id`, so it drops into the one id-keyed store and its identity is indexed.
(rf/reg-event-db ::fetch-public-ok
                 (fn [db [_ view-kind response]]
                   (let [doc (kw-kind (:body response))]
                     (-> db
                         (cache-put-doc doc)
                         (assoc :view {:kind view-kind
                                       :data doc}
                                :loading? false)))))

(defn- route-changed-fetch-list
  "Fetch the browse list for document `type` (`:ki` / `:article`), across **all** content languages
  (the client filters by language) and scoped to the active publication, stored under `view-kind`
  (`:home` landing, `:discover` / `:articles` grid). Not cached — each visit re-fetches so the
  visit-weighted random order refreshes."
  [db type view-kind]
  {:db (assoc db :loading? true :error nil)
   :fetch
   {:method :get
    :url
    (str "/agora/api/documents/" (name type) "?lang=all" "&limit=50" (publications/active-param db))
    :headers {"Accept" "application/json"}
    :response-content-types {#"application/json" :json}
    :on-success [::fetch-list-ok view-kind]
    :on-failure [::fetch-failed]}})

(rf/reg-event-db ::fetch-list-ok
                 (fn [db [_ view-kind response]]
                   (assoc db
                          :view {:kind view-kind
                                 :data (mapv kw-kind (:body response))}
                          :loading? false)))

(defn- route-changed-fetch-article-public
  "Cache-or-fetch the public article permalink /agora/{lang}/article/{name}/{major}."
  [db art-name art-major lang]
  (let [entry (get-in db [:cache (get-in db [:tnlr (tnlr-key :article lang art-name art-major)])])
        fresh? (and entry (< (- (js/Date.now) (:at entry)) cache-ttl-ms))]
    (if fresh?
      {:db (assoc db
                  :view {:kind :article-public
                         :data (:data entry)}
                  :loading? false
                  :error nil)}
      {:db (assoc db :loading? true :error nil)
       :fetch {:method :get
               :url (str "/agora/api/documents/article/" (js/encodeURIComponent art-name)
                         "/" lang
                         "/" art-major)
               :headers {"Accept" "application/json"}
               :response-content-types {#"application/json" :json}
               :on-success [::fetch-public-ok :article-public]
               :on-failure [::fetch-failed]}})))

;; Author profile: the author card + their documents + last activity, scoped to the
;; interface language. Cached by [:author id lang].
(defn- route-changed-fetch-author
  [db id lang]
  (let [ck [:author id lang]
        entry (get-in db [:cache ck])
        fresh? (and entry (< (- (js/Date.now) (:at entry)) cache-ttl-ms))]
    (if fresh?
      {:db (assoc db
                  :view {:kind :author
                         :data (:data entry)}
                  :loading? false
                  :error nil)}
      {:db (assoc db :loading? true :error nil)
       :fetch {:method :get
               :url (str "/agora/api/author/" (js/encodeURIComponent id) "?lang=" lang)
               :headers {"Accept" "application/json"}
               :response-content-types {#"application/json" :json}
               :on-success [::fetch-author-ok ck]
               :on-failure [::fetch-failed]}})))

(rf/reg-event-fx
 ::route-changed
 (fn [{:keys [db]} [_ {:keys [kind id name major lang]}]]
   ;; The interface language is a preference, NOT the URL — so a
   ;; route change never touches it. The URL `lang` is used only as
   ;; the content language of a KI permalink; discover/search follow
   ;; the preference.
   ;; remember what we're navigating to so the loading state can show
   ;; a matching skeleton (a KI card vs the discover grid).
   (let [db (assoc (edit/close-panels db) :loading-kind kind)]
     (case kind
       :new {:db (assoc db
                        :view {:kind :new
                               :data nil}
                        :loading? false
                        :error nil)}
       :preferences {:db (assoc db
                                :view {:kind :preferences
                                       :data nil}
                                :loading? false
                                :error nil)}
       :admin {:db (assoc db
                          :view {:kind :admin
                                 :data nil}
                          :loading? false
                          :error nil)
               :dispatch [:agora/admin-fetch]}
       :publications {:db (assoc db
                                 :view {:kind :publications
                                        :data nil}
                                 :loading? false
                                 :error nil)
                      :dispatch [::publications/search]}
       :publication {:db (assoc db
                                :view {:kind :publication
                                       :data nil}
                                :loading? false
                                :error nil)
                     :dispatch [::publications/load-page id]}
       :publication-graph {:db (assoc db
                                      :view {:kind :publication-graph
                                             :id id}
                                      :loading? false
                                      :error nil)
                           :dispatch [::graph/load id]}
       :ki-public (route-changed-fetch-public db name major lang)
       ;; a signed-in author with an active publication lands on it, not the generic home
       :home (if-let [pub-id (:id (:agora/active-publication db))]
               {:agora/navigate (i18n/publication (i18n/current db) pub-id)}
               (route-changed-fetch-list db :ki :home))
       :discover (route-changed-fetch-list db :ki :discover)
       :articles (route-changed-fetch-list db :article :articles)
       ;; the browse-by-author / browse-by-source views self-fetch (local search state)
       :authors {:db (assoc db
                            :view {:kind :authors
                                   :data nil}
                            :loading? false
                            :error nil)}
       :sources {:db (assoc db
                            :view {:kind :sources
                                   :data nil}
                            :loading? false
                            :error nil)}
       :article-new {:db (assoc db
                                :view {:kind :article-new
                                       :data nil}
                                :loading? false
                                :error nil)}
       :article-public (route-changed-fetch-article-public db name major lang)
       :author (route-changed-fetch-author db id (i18n/current db))
       (route-changed-fetch db kind id)))))

(rf/reg-event-fx ::refetch-scoped
                 (fn [{:keys [db]} _]
                   ;; the active publication changed — re-fetch the current discover grid so its draft overlay
                   ;; updates without a navigation. Dispatched by `publications/set-active`+`clear-active`.
                   (case (:kind (:view db))
                     :home (route-changed-fetch-list db :ki :home)
                     :discover (route-changed-fetch-list db :ki :discover)
                     :articles (route-changed-fetch-list db :article :articles)
                     ;; a single-doc view: re-fetch it by id (forced, bypassing the cache) so its
                     ;; publication-scoped successors/inputs overlay reflects the current publication —
                     ;; e.g. after a consequence is created on this page
                     (:ki :article) (let [vk (:kind (:view db))
                                          id (:id (:data (:view db)))]
                                      (if id
                                        {:db (assoc db :loading? true)
                                         :fetch
                                         {:method :get
                                          :url (str (api-path vk) id (publications/active-query db))
                                          :headers {"Accept" "application/json"}
                                          :response-content-types {#"application/json" :json}
                                          :on-success [::fetch-ok vk id]
                                          :on-failure [::fetch-failed]}}
                                        {}))
                     {})))

(rf/reg-event-db ::fetch-ok
                 (fn [db [_ kind _id response]]
                   (let [doc (kw-kind (:body response))]
                     (-> db
                         (cache-put-doc doc)
                         (assoc :view {:kind kind
                                       :data doc}
                                :loading? false)))))

(rf/reg-event-db ::fetch-author-ok
                 (fn [db [_ ck response]]
                   ;; keyword each listed document's `:kind` at the wire boundary, like the browse feed
                   (let [a (update (:body response) :documents #(mapv kw-kind %))]
                     (-> db
                         (assoc :view {:kind :author
                                       :data a}
                                :loading? false)
                         (cache-put ck a)))))

(rf/reg-event-db ::fetch-failed
                 (fn [db [_ response]]
                   (js/console.error "[agora] fetch failed:" (clj->js response))
                   (assoc db :loading? false :error response)))

;; Neighbour cards fetch their KI by id (the response now carries bare {:id} refs).
;; This fills the cache without touching the current view, and skips already-cached.
(rf/reg-event-fx :agora/ensure-ki
                 (fn [{:keys [db]} [_ id]]
                   (when-not (get-in db [:cache id])
                     {:fetch {:method :get
                              :url (str "/agora/api/documents/ki/" id)
                              :headers {"Accept" "application/json"}
                              :response-content-types {#"application/json" :json}
                              :on-success [::neighbour-ok]
                              :on-failure [::fetch-failed]}})))

(rf/reg-event-db ::neighbour-ok (fn [db [_ response]] (cache-put-doc db (:body response))))

;; Article KI-citations are *living* references (name + major → latest minor), so
;; they resolve through the by-TNLR endpoint, not by id. The returned document lands
;; in the one id-keyed store, so citing a KI in an article also warms its permalink.
;; Skips already-cached.
(rf/reg-event-fx :agora/ensure-ki-by-major
                 (fn [{:keys [db]} [_ ki-name ki-major lang]]
                   (when-not (doc-by-tnlr db :ki lang ki-name ki-major)
                     {:fetch {:method :get
                              :url (str "/agora/api/documents/ki/" (js/encodeURIComponent ki-name)
                                        "/" lang
                                        "/" ki-major)
                              :headers {"Accept" "application/json"}
                              :response-content-types {#"application/json" :json}
                              :on-success [::cite-ok]
                              :on-failure [::fetch-failed]}})))

(rf/reg-event-db ::cite-ok (fn [db [_ response]] (cache-put-doc db (:body response))))

;; The cached living-citation doc for (name, major) in `lang` (nil until it lands).
(rf/reg-sub :agora/cite-doc
            (fn [db [_ ki-name ki-major lang]] (doc-by-tnlr db :ki lang ki-name ki-major)))

;; Generic (any-type) by-major fetch + sub — used by hover previews of a node
;; (KI or article) wherever we only hold its identity (e.g. the admin page).
(rf/reg-event-fx :agora/ensure-by-major
                 (fn [{:keys [db]} [_ type doc-name doc-major lang]]
                   (when-not (doc-by-tnlr db type lang doc-name doc-major)
                     {:fetch {:method :get
                              :url (str "/agora/api/documents/" type
                                        "/" (js/encodeURIComponent doc-name)
                                        "/" lang
                                        "/" doc-major)
                              :headers {"Accept" "application/json"}
                              :response-content-types {#"application/json" :json}
                              :on-success [::cite-ok]
                              :on-failure [::fetch-failed]}})))

(rf/reg-sub :agora/node-doc
            (fn [db [_ type doc-name doc-major lang]]
              (doc-by-tnlr db type lang doc-name doc-major)))

;; Generic (any-type) by-**id** fetch + sub — a *pinned* version. Unlike by-major
;; (which resolves to the latest minor), this returns the exact version, so a hover
;; preview of a former version shows *that* version's content. Used by admin deep-links
;; to a specific version.
(rf/reg-event-fx :agora/ensure-by-id
                 (fn [{:keys [db]} [_ type id]]
                   (when-not (get-in db [:cache id])
                     {:fetch {:method :get
                              :url (str "/agora/api/documents/" type "/" id)
                              :headers {"Accept" "application/json"}
                              :response-content-types {#"application/json" :json}
                              :on-success [::cite-ok]
                              :on-failure [::fetch-failed]}})))

(rf/reg-sub :agora/node-doc-by-id (fn [db [_ _type id]] (doc-by-id db id)))

;; Lazy version list for the admin version picker: fetched only when the picker opens, keyed by the
;; current version's id, so a normal read never pays for admin-only version nav. Skips already-fetched.
(rf/reg-event-fx :agora/ensure-versions
                 (fn [{:keys [db]} [_ type id]]
                   (when-not (get-in db [:versions id])
                     {:fetch {:method :get
                              :url (str "/agora/api/documents/" type "/" id "/versions")
                              :headers {"Accept" "application/json"}
                              :response-content-types {#"application/json" :json}
                              :on-success [::versions-ok id]
                              :on-failure [::fetch-failed]}})))

(rf/reg-event-db ::versions-ok (fn [db [_ id resp]] (assoc-in db [:versions id] (:body resp))))

(rf/reg-sub :agora/versions (fn [db [_ id]] (get-in db [:versions id])))

;; Called after any successful create/edit (both document types): ingest the new version
;; locally under its by-id cache key and navigate to its concrete-version app URL — no
;; refetch. Generic: the type comes off the returned document, so there is no per-type
;; handler.
(rf/reg-event-fx :agora/saved
                 (fn [{:keys [db]} [_ doc]]
                   (let [type (:type doc)
                         id (:id doc)]
                     {:db (-> db
                              (edit/close-panels)
                              ;; bust the whole lineage first (old minor + permalink + previews),
                              ;; then cache the freshly saved version by its new id
                              (cache-evict-lineage doc)
                              (cache-put-doc doc)
                              ;; render the saved version immediately. Publish keeps the SAME id, so
                              ;; the navigate below is a no-op (already on this URL) and would leave
                              ;; the stale draft on screen; setting :view here refreshes regardless.
                              (assoc :view {:kind (keyword type)
                                            :data doc}
                                     :loading? false))
                      :agora/navigate (i18n/doc-url (i18n/current db) type id)})))

;; Remove an input edge via the input field (the ✕ on an input card) — DELETE the edge
;; on the server, which forks a new minor (also stripping the inline citation from the
;; text). Then ingest the returned version and navigate to it, exactly like an edit, so
;; removal is a first-class input-field action rather than hand-editing the text.
(rf/reg-event-fx :agora/drop-input
                 (fn [{:keys [db]} [_ type id input]]
                   {:db (assoc db :loading? true :error nil)
                    :fetch {:method :delete
                            :url (str "/agora/api/" type "/" id "/inputs")
                            :headers {"Content-Type" "application/json"
                                      "Accept" "application/json"}
                            :body (js/JSON.stringify (clj->js input))
                            :response-content-types {#"application/json" :json}
                            :on-success [::input-dropped type]
                            :on-failure [::fetch-failed]}}))

(rf/reg-event-fx ::input-dropped
                 (fn [_ [_ _type response]]
                   (let [doc (:body response)] (when (:id doc) {:dispatch [:agora/saved doc]}))))

;; Programmatic navigation: drive Pushy so it pushState's and dispatches the
;; route change, same as an intercepted link click.
(rf/reg-fx :agora/navigate (fn [url] (pushy/set-token! @history url)))

;; Event form of the above, for components (e.g. the language switcher) to call.
(rf/reg-event-fx :agora/goto (fn [_ [_ url]] {:agora/navigate url}))

;; Esc on the creation page → back to discover (like Cancel), but only when no
;; modal (e.g. the login prompt) is open over it — that modal handles Esc itself and
;; must not also trigger a navigation that would discard the half-filled form.
(rf/reg-event-fx :agora/cancel-new
                 (fn [{:keys [db]} [_ url]]
                   (when-not (::auth/form db)
                     {:agora/navigate (or url (i18n/discover (i18n/current db)))})))

;; ---------------------------------------------------------------------------
;; Interface-language preference
;;
;; The preference drives the chrome, the discover feed and search. It is cached in
;; localStorage (all users) and, for logged-in users, persisted to AGORA_USER (a
;; 401 for anonymous users is harmless — the localStorage copy is authoritative
;; for them). Changing it never rewrites a KI permalink you are viewing; if you are
;; on discover it refetches the feed in the new language.
;; ---------------------------------------------------------------------------

(rf/reg-event-db ::pref-saved (fn [db _] db))

(rf/reg-event-fx :agora/set-lang
                 (fn [{:keys [db]} [_ lang]]
                   (let [l (language/normalize lang)
                         on-discover? (= :discover (get-in db [:view :kind]))]
                     (i18n/write-stored! l)
                     (cond-> {:db (i18n/set-lang db l)
                              :fetch {:method :post
                                      :url "/agora/api/auth/lang"
                                      :headers {"Content-Type" "application/json"
                                                "Accept" "application/json"}
                                      :body (js/JSON.stringify (clj->js {:lang l}))
                                      :response-content-types {#"application/json" :json}
                                      :on-success [::pref-saved]
                                      :on-failure [::pref-saved]}}
                       on-discover? (assoc :agora/navigate (i18n/discover l))))))

;; Adopt a language into the preference without persisting (it already came from a
;; trusted source — localStorage at boot, or the account at login).
(rf/reg-event-fx :agora/adopt-lang
                 (fn [{:keys [db]} [_ lang]]
                   (i18n/write-stored! (language/normalize lang))
                   {:db (i18n/set-lang db lang)}))

;; ---------------------------------------------------------------------------
;; Admin — list / prune KI lineages (TNRs)
;; ---------------------------------------------------------------------------

(rf/reg-event-db ::admin-tnrs-ok
                 (fn [db [_ resp]] (assoc db :admin-tnrs (mapv kw-kind (:body resp)))))

;; On failure (403 not-admin, or 503 DB down) show an empty list rather than
;; assoc-ing the error body — the admin table iterates :admin-tnrs.
(rf/reg-event-db ::admin-tnrs-failed (fn [db _] (assoc db :admin-tnrs [])))

(rf/reg-event-db ::admin-issues-ok
                 (fn [db [_ resp]] (assoc db :admin-issues (mapv kw-kind (:body resp)))))
(rf/reg-event-db ::admin-issues-failed (fn [db _] (assoc db :admin-issues [])))

(rf/reg-event-fx ::admin-issues-fetch
                 (fn [_ _]
                   {:fetch {:method :get
                            :url "/agora/api/admin/issues"
                            :headers {"Accept" "application/json"}
                            :response-content-types {#"application/json" :json}
                            :on-success [::admin-issues-ok]
                            :on-failure [::admin-issues-failed]}}))

(rf/reg-event-fx :agora/admin-fetch
                 (fn [_ _]
                   {:fetch {:method :get
                            :url "/agora/api/admin/tnrs"
                            :headers {"Accept" "application/json"}
                            :response-content-types {#"application/json" :json}
                            :on-success [::admin-tnrs-ok]
                            :on-failure [::admin-tnrs-failed]}
                    :dispatch [::admin-issues-fetch]}))

(defn- admin-post
  [url type doc-name lang doc-major]
  {:method :post
   :url url
   :headers {"Content-Type" "application/json"
             "Accept" "application/json"}
   :body (js/JSON.stringify (clj->js {:type type
                                      :name doc-name
                                      :lang lang
                                      :major doc-major}))
   :response-content-types {#"application/json" :json}
   :on-success [:agora/admin-fetch]
   :on-failure [:agora/admin-fetch]})

(rf/reg-event-fx :agora/admin-drop
                 (fn [_ [_ type doc-name lang doc-major]]
                   {:fetch (admin-post "/agora/api/admin/drop-tnr" type doc-name lang doc-major)}))

(rf/reg-event-fx :agora/admin-compact
                 (fn [_ [_ type doc-name lang doc-major]]
                   {:fetch
                    (admin-post "/agora/api/admin/compact-tnr" type doc-name lang doc-major)}))

(rf/reg-sub ::view (fn [db _] (:view db)))
;; The cached document for a KI id (nil until a neighbour fetch lands).
(rf/reg-sub :agora/doc (fn [db [_ id]] (doc-by-id db id)))
(rf/reg-sub ::loading? (fn [db _] (:loading? db)))
(rf/reg-sub ::loading-kind (fn [db _] (:loading-kind db)))
(rf/reg-sub ::error (fn [db _] (:error db)))

;; ---------------------------------------------------------------------------
;; View
;; ---------------------------------------------------------------------------

(defn- error-view
  [error]
  [:pre {:style {:font-family "monospace"
                 :font-size "0.9em"
                 :white-space "pre-wrap"
                 :background "#f5f5f5"
                 :padding "1em"
                 :border-left "3px solid #c92a2a"}}
   (str "fetch failed:\n\n" (with-out-str (pprint error)))])

(defn app-view
  []
  (let [{:keys [kind data id]} @(rf/subscribe [::view])
        user @(rf/subscribe [::auth/user])
        loading? @(rf/subscribe [::loading?])
        error @(rf/subscribe [::error])]
    (cond
      (= kind :new) [ki-page/create-form]
      (= kind :article-new) [article-page/create-form]
      (= kind :preferences) [preferences-page/preferences-page]
      (= kind :authors) [find-page/authors-page]
      (= kind :sources) [find-page/sources-page]
      (= kind :admin) [admin/admin-page]
      (= kind :publications) [publications/publications-page]
      (= kind :publication) [publications/publication-page]
      (= kind :publication-graph) [graph/graph-page id]
      ;; Keep showing the current resource whenever we have one — even while the
      ;; next is being fetched. The view swaps only on data arrival.
      data (case kind
             :ki [ki-page/page data]
             ;; A KI permalink renders read-only for anonymous visitors and the
             ;; editable page for signed-in ones. Reactive on the auth sub, so
             ;; it swaps the moment /me resolves after a hard load.
             :ki-public (if user [ki-page/page data] [ki-page/public-page data])
             :home [landing/landing-page data]
             :discover [ki-page/discover data]
             :articles [article-page/discover data]
             :article [article-page/page data]
             :article-public [article-page/page data]
             :author [author-page/author-page data]
             nil)
      error [error-view error]
      loading? [loading/loading-view @(rf/subscribe [::loading-kind])]
      :else nil)))

(defn root-view
  "The shared header, the current page, and the footer. A flex column at least as
  tall as the viewport keeps the footer at the bottom: it sits against the bottom
  edge when content is short, and is pushed below when content overflows."
  []
  [:div {:style {:display "flex"
                 :flex-direction "column"
                 :min-height "100vh"}}
   [chrome/header]
   [:main {:style {:flex "1 0 auto"
                   :min-width 0}}
    [app-view]]
   [chrome/site-footer]
   [auth/auth-modal]
   [modal/confirm-modal]
   [document-page/translation-editor]])

(defn ^:dev/after-load mount-root
  []
  (rf/clear-subscription-cache!)
  (when-let [el (.getElementById js/document "agora-app")]
    (rdom/unmount-component-at-node el)
    (rdom/render [root-view] el)))

(defn init
  []
  (js/console.log "[agora] frontend started")
  ;; Seed the interface-language preference from localStorage/cookie/browser before
  ;; the first paint; a logged-in user's account preference then overrides it when
  ;; /me returns (see auth ::me-ok → :agora/adopt-lang).
  (rf/dispatch-sync [:agora/adopt-lang (i18n/initial-pref)])
  ;; adopt the active publication (the work-package create/edit attach to) from localStorage
  (rf/dispatch [::publications/adopt-active])
  (rf/dispatch [::auth/check])
  (reset! history (pushy/pushy #(rf/dispatch [::route-changed %]) path->route))
  ;; `start!` replays the current URL through the processor, dispatching the initial
  ;; `::route-changed` itself — so there is no separate initial dispatch (an explicit one
  ;; would double every route-driven fetch on first paint).
  (pushy/start! @history)
  (mount-root))
