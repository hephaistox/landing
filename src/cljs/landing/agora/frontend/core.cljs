(ns landing.agora.frontend.core
  "Agora frontend entry point — a small single-page app over the hidden lab
  routes.

  Pushy (HTML5 history) intercepts in-app links and programmatic navigation, so
  moving between KIs/articles and landing on a freshly edited version updates the
  view via re-frame without a full page reload. The backend still serves the same
  shell for every `/lab/...` path, so direct URLs and refresh keep working.

  Fetched resources are cached by [kind id] with a time-to-live; the displayed
  view only swaps once data is available, so navigation never flashes a Loading
  state. A local latest-minor index lets neighbour links auto-resolve to the
  newest known version (e.g. right after an edit) without hitting the server."
  (:require
   [cljs.pprint                         :refer [pprint]]
   [landing.agora.frontend.article-view :as article-view]
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
  {:ki "/api/ki/"
   :article "/api/article/"})

(def ^:private cache-ttl-ms
  "How long a cached resource stays fresh before it is refetched (4 hours)."
  (* 4 60 60 1000))

(defn path->route
  "Parse a lab path into {:kind …}, or nil when it is not a lab route (Pushy then
  lets the browser handle the click normally)."
  [path]
  (cond
    (re-find #"^/lab/ki/new/?(?:[?#].*)?$" path) {:kind :new}
    (re-find #"^/lab/article/([^/?#]+)" path)
    {:kind :article
     :id (js/decodeURIComponent (second (re-find #"^/lab/article/([^/?#]+)" path)))}
    :else (when-let [m (re-find #"^/lab/ki(?:/([^/?#]+))?/?(?:[?#].*)?$" path)]
            {:kind :ki
             :id (or (some-> (second m)
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

(rf/reg-event-fx ::route-changed
                 (fn [{:keys [db]} [_ {:keys [kind id]}]]
                   (let [db (ki-view/close-panels db)]
                     (if (= kind :new)
                       ;; The creation form — nothing to fetch.
                       {:db (assoc db
                                   :view {:kind :new
                                          :data nil}
                                   :loading? false
                                   :error nil)}
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
                      :agora/navigate (str "/lab/ki/" id)})))

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
              (if (= :ki (:kind view)) (update view :data #(resolve-ki-neighbours latest %)) view)))

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
        loading? @(rf/subscribe [::loading?])
        error @(rf/subscribe [::error])]
    (cond
      (= kind :new) [ki-view/creation-form]
      ;; Keep showing the current resource whenever we have one — even while the
      ;; next is being fetched. The view swaps only on data arrival.
      data (case kind
             :ki [ki-view/ki-page data]
             :article [article-view/article-card data]
             nil)
      error [error-view error]
      loading? [:p {:style {:color "#888"}}
                "Loading…"]
      :else nil)))

(defn ^:dev/after-load mount-root
  []
  (rf/clear-subscription-cache!)
  (when-let [el (.getElementById js/document "agora-app")]
    (rdom/unmount-component-at-node el)
    (rdom/render [app-view] el)))

(defn init
  []
  (js/console.log "[agora] frontend started")
  (reset! history (pushy/pushy #(rf/dispatch [::route-changed %]) path->route))
  (pushy/start! @history)
  ;; Dispatch the initial route explicitly so the first paint is correct
  ;; regardless of whether start! replays the current URL.
  (when-let [route (path->route (.. js/window -location -pathname))]
    (rf/dispatch [::route-changed route]))
  (mount-root))
