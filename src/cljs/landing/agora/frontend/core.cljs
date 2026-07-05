(ns landing.agora.frontend.core
  "Agora frontend entry point — a small single-page app over the hidden lab
  routes.

  Pushy (HTML5 history) intercepts in-app links and programmatic navigation, so
  moving between KIs/articles and landing on a freshly edited version updates the
  view via re-frame without a full page reload. The backend still serves the same
  shell for every `/agora/lab/...` path, so direct URLs and refresh keep working.

  Fetched resources are cached by [kind id] with a time-to-live; the displayed
  view only swaps once data is available, so navigation never flashes a Loading
  state. A local latest-minor index lets neighbour links auto-resolve to the
  newest known version (e.g. right after an edit) without hitting the server."
  (:require
   [cljs.pprint                         :refer [pprint]]
   [landing.agora.frontend.article-view :as article-view]
   [landing.agora.frontend.auth         :as auth]
   [landing.agora.frontend.i18n         :as i18n]
   [landing.agora.frontend.ki-view      :as ki-view]
   [pushy.core                          :as pushy]
   [re-frame.core                       :as rf]
   [reagent.dom                         :as rdom]
   [superstructor.re-frame.fetch-fx]))

(goog-define ENV "dev")

(def seeded-ki-id
  "The KI seeded in #40; used as a fallback when no id is given in the URL."
  "00000000-0000-0000-0000-000000000001")

(def ^:private api-path
  {:ki "/agora/api/ki/"
   :article "/agora/api/article/"})

(def ^:private cache-ttl-ms
  "How long a cached resource stays fresh before it is refetched (4 hours)."
  (* 4 60 60 1000))

(defn path->route
  "Parse a lab path into {:kind …}, or nil when it is not a lab route (Pushy then
  lets the browser handle the click normally)."
  [path]
  (cond
    (re-find #"^/agora/([a-z]{2})/discover/?(?:[?#].*)?$" path)
    {:kind :discover
     :lang (second (re-find #"^/agora/([a-z]{2})/discover" path))}
    (re-find #"^/agora/([a-z]{2})/preferences/?(?:[?#].*)?$" path)
    {:kind :preferences
     :lang (second (re-find #"^/agora/([a-z]{2})/preferences" path))}
    (re-find #"^/agora/([a-z]{2})/admin/?(?:[?#].*)?$" path)
    {:kind :admin
     :lang (second (re-find #"^/agora/([a-z]{2})/admin" path))}
    (re-find #"^/agora/([a-z]{2})/ki/([^/?#]+)/([0-9]+)/?(?:[?#].*)?$" path)
    (let [[_ lang n mj] (re-find #"^/agora/([a-z]{2})/ki/([^/?#]+)/([0-9]+)" path)]
      {:kind :ki-public
       :lang lang
       :name (js/decodeURIComponent n)
       :major (js/parseInt mj)})
    (re-find #"^/agora/([a-z]{2})/lab/ki/new/?(?:[?#].*)?$" path)
    {:kind :new
     :lang (second (re-find #"^/agora/([a-z]{2})/" path))}
    (re-find #"^/agora/([a-z]{2})/lab/article/([^/?#]+)" path)
    (let [[_ lang id] (re-find #"^/agora/([a-z]{2})/lab/article/([^/?#]+)" path)]
      {:kind :article
       :lang lang
       :id (js/decodeURIComponent id)})
    :else (when-let [m (re-find #"^/agora/([a-z]{2})/lab/ki(?:/([^/?#]+))?/?(?:[?#].*)?$" path)]
            {:kind :ki
             :lang (second m)
             :id (or (some-> (nth m 2)
                             js/decodeURIComponent)
                     seeded-ki-id)})))

(defonce ^:private history (atom nil))

;; ---------------------------------------------------------------------------
;; Local latest-minor index
;;
;; :latest {[name major] {:id id :minor minor}} — the newest KI version we
;; know about for each major lineage, learned from fetched/edited data. Neighbour
;; links resolve through it, mirroring the server's resolve-major, so an edit is
;; reflected everywhere immediately.
;; ---------------------------------------------------------------------------

(defn- note-latest
  "Fold a KI-ish ref {:name :major :minor :id} into the latest-minor index. Keyed
  on (name, major) — identity's T is the object type `ki`."
  [latest {:keys [name major minor id]}]
  (if (and name major id (some? minor))
    (let [k [name major]
          cur (get latest k)]
      (if (or (nil? cur) (> minor (:minor cur)))
        (assoc latest
               k
               {:id id
                :minor minor})
        latest))
    latest))

(defn- index-ki
  "Index a KI and its neighbours into the latest-minor map."
  [latest ki]
  (reduce note-latest latest (concat [ki] (:inputs ki) (:successors ki))))

(defn- resolve-neighbour
  "Point a neighbour ref at the newest known minor of its (name, major) lineage."
  [latest
   {:keys [name major minor]
    :as n}]
  (let [newer (get latest [name major])]
    (if (and newer (> (:minor newer) minor)) (assoc n :id (:id newer) :minor (:minor newer)) n)))

(defn- resolve-ki-neighbours
  [latest ki]
  (-> ki
      (update :inputs #(mapv (partial resolve-neighbour latest) %))
      (update :successors #(mapv (partial resolve-neighbour latest) %))))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defn- route-changed-fetch
  "Cache-or-fetch a :ki/:article route."
  [db kind id]
  (let [entry (get-in db [:cache [kind id]])
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
       :fetch {:method :get
               :url (str (api-path kind) id)
               :headers {"Accept" "application/json"}
               :response-content-types {#"application/json" :json}
               :on-success [::fetch-ok kind id]
               :on-failure [::fetch-failed]}})))

(defn- route-changed-fetch-public
  "Cache-or-fetch the public permalink /agora/{lang}/ki/{name}/{major}. The `lang`
  comes from the URL — it is the content language to display for this KI, which is
  independent of the interface-language preference (the permalink overrides it)."
  [db ki-name ki-major lang]
  (let [ck [:ki-public lang ki-name ki-major]
        entry (get-in db [:cache ck])
        fresh? (and entry (< (- (js/Date.now) (:at entry)) cache-ttl-ms))]
    (if fresh?
      {:db (assoc db
                  :view {:kind :ki-public
                         :data (:data entry)}
                  :loading? false
                  :error nil)}
      {:db (assoc db :loading? true :error nil)
       :fetch {:method :get
               :url
               (str "/agora/api/ki/by/" (js/encodeURIComponent ki-name) "/" ki-major "?lang=" lang)
               :headers {"Accept" "application/json"}
               :response-content-types {#"application/json" :json}
               :on-success [::fetch-public-ok ck]
               :on-failure [::fetch-failed]}})))

(rf/reg-event-db ::fetch-public-ok
                 (fn [db [_ ck response]]
                   (let [ki (:body response)]
                     (-> db
                         (assoc :view {:kind :ki-public
                                       :data ki}
                                :loading? false)
                         (assoc-in [:cache ck]
                                   {:data ki
                                    :at (js/Date.now)})
                         (update :latest index-ki ki)))))

(defn- route-changed-fetch-list
  "Fetch the discoverability KI list (GET /agora/api/ki?lang=), scoped to the
  current content language. Not cached — each visit re-fetches so the
  visit-weighted random order refreshes."
  [db lang]
  {:db (assoc db :loading? true :error nil)
   :fetch {:method :get
           :url (str "/agora/api/ki?lang=" lang)
           :headers {"Accept" "application/json"}
           :response-content-types {#"application/json" :json}
           :on-success [::fetch-list-ok]
           :on-failure [::fetch-failed]}})

(rf/reg-event-db ::fetch-list-ok
                 (fn [db [_ response]]
                   (assoc db
                          :view {:kind :discover
                                 :data (:body response)}
                          :loading? false)))

(rf/reg-event-fx ::route-changed
                 (fn [{:keys [db]} [_ {:keys [kind id name major lang]}]]
                   ;; The interface language is a preference, NOT the URL — so a
                   ;; route change never touches it. The URL `lang` is used only as
                   ;; the content language of a KI permalink; discover/search follow
                   ;; the preference.
                   (let [db (ki-view/close-panels db)]
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
                       :ki-public (route-changed-fetch-public db name major lang)
                       :discover (route-changed-fetch-list db (i18n/current db))
                       (route-changed-fetch db kind id)))))

(rf/reg-event-db ::fetch-ok
                 (fn [db [_ kind id response]]
                   (let [data (:body response)]
                     (cond-> (-> db
                                 (assoc :view {:kind kind
                                               :data data}
                                        :loading? false)
                                 (assoc-in [:cache [kind id]]
                                           {:data data
                                            :at (js/Date.now)}))
                       (= kind :ki) (update :latest index-ki data)))))

(rf/reg-event-db ::fetch-failed
                 (fn [db [_ response]]
                   (js/console.error "[agora] fetch failed:" (clj->js response))
                   (assoc db :loading? false :error response)))

;; Called after a successful edit: ingest the new version locally (cache + latest
;; index) and navigate to it — no refetch, and references resolve to it at once.
(rf/reg-event-fx :agora/edited
                 (fn [{:keys [db]} [_ ki]]
                   (let [id (:id ki)]
                     {:db (-> db
                              (ki-view/close-panels)
                              (assoc-in [:cache [:ki id]]
                                        {:data ki
                                         :at (js/Date.now)})
                              (update :latest index-ki ki))
                      :agora/navigate (i18n/lab-ki (i18n/current db) id)})))

;; Called after a link change (add/drop input): the same KI id is refreshed in
;; place — update the view, cache and latest index, no navigation.
(rf/reg-event-db :agora/ki-updated
                 (fn [db [_ ki]]
                   (-> db
                       (assoc :view
                              {:kind :ki
                               :data ki})
                       (assoc-in [:cache [:ki (:id ki)]]
                                 {:data ki
                                  :at (js/Date.now)})
                       (update :latest index-ki ki))))

;; Programmatic navigation: drive Pushy so it pushState's and dispatches the
;; route change, same as an intercepted link click.
(rf/reg-fx :agora/navigate (fn [url] (pushy/set-token! @history url)))

;; Event form of the above, for components (e.g. the language switcher) to call.
(rf/reg-event-fx :agora/goto (fn [_ [_ url]] {:agora/navigate url}))

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
                   (let [l (i18n/normalize lang)
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
                   (i18n/write-stored! (i18n/normalize lang))
                   {:db (i18n/set-lang db lang)}))

;; ---------------------------------------------------------------------------
;; Admin — list / prune KI lineages (TNRs)
;; ---------------------------------------------------------------------------

(rf/reg-event-db ::admin-tnrs-ok (fn [db [_ resp]] (assoc db :admin-tnrs (:body resp))))

(rf/reg-event-fx :agora/admin-fetch
                 (fn [_ _]
                   {:fetch {:method :get
                            :url "/agora/api/admin/tnrs"
                            :headers {"Accept" "application/json"}
                            :response-content-types {#"application/json" :json}
                            :on-success [::admin-tnrs-ok]
                            :on-failure [::admin-tnrs-ok]}}))

(defn- admin-post
  [url ki-name ki-major]
  {:method :post
   :url url
   :headers {"Content-Type" "application/json"
             "Accept" "application/json"}
   :body (js/JSON.stringify (clj->js {:name ki-name
                                      :major ki-major}))
   :response-content-types {#"application/json" :json}
   :on-success [:agora/admin-fetch]
   :on-failure [:agora/admin-fetch]})

(rf/reg-event-fx :agora/admin-drop
                 (fn [_ [_ ki-name ki-major]]
                   {:fetch (admin-post "/agora/api/admin/drop-tnr" ki-name ki-major)}))

(rf/reg-event-fx :agora/admin-compact
                 (fn [_ [_ ki-name ki-major]]
                   {:fetch (admin-post "/agora/api/admin/compact-tnr" ki-name ki-major)}))

(rf/reg-sub ::view (fn [db _] (:view db)))
(rf/reg-sub ::latest (fn [db _] (:latest db)))
(rf/reg-sub ::loading? (fn [db _] (:loading? db)))
(rf/reg-sub ::error (fn [db _] (:error db)))

;; The view with KI neighbours re-resolved through the local latest-minor index,
;; so links always point at the newest known version.
(rf/reg-sub ::resolved-view
            :<-
            [::view]
            :<-
            [::latest]
            (fn [[view latest] _]
              (if (#{:ki :ki-public} (:kind view))
                (update view :data #(resolve-ki-neighbours latest %))
                view)))

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
  (let [{:keys [kind data]} @(rf/subscribe [::resolved-view])
        user @(rf/subscribe [::auth/user])
        loading? @(rf/subscribe [::loading?])
        error @(rf/subscribe [::error])]
    (cond
      (= kind :new) [ki-view/creation-form]
      (= kind :preferences) [ki-view/preferences-page]
      (= kind :admin) [ki-view/admin-page]
      ;; Keep showing the current resource whenever we have one — even while the
      ;; next is being fetched. The view swaps only on data arrival.
      data (case kind
             :ki [ki-view/ki-page data]
             ;; A KI permalink renders read-only for anonymous visitors and the
             ;; editable lab page for signed-in ones. Reactive on the auth sub, so
             ;; it swaps the moment /me resolves after a hard load.
             :ki-public (if user [ki-view/ki-page data] [ki-view/public-ki-page data])
             :discover [ki-view/discover-page data]
             :article [article-view/article-card data]
             nil)
      error [error-view error]
      loading? [:p {:style {:color "#888"}}
                "Loading…"]
      :else nil)))

(defn root-view
  "The shared header on top of the current page, plus the auth modal overlay."
  []
  [:div
   [ki-view/header]
   [app-view]
   [ki-view/site-footer]
   [auth/auth-modal]
   [ki-view/translation-editor]])

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
  (rf/dispatch [::auth/check])
  (reset! history (pushy/pushy #(rf/dispatch [::route-changed %]) path->route))
  (pushy/start! @history)
  ;; Dispatch the initial route explicitly so the first paint is correct
  ;; regardless of whether start! replays the current URL.
  (when-let [route (path->route (.. js/window -location -pathname))]
    (rf/dispatch [::route-changed route]))
  (mount-root))
