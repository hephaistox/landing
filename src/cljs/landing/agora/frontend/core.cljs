(ns landing.agora.frontend.core
  "Agora frontend bootstrap (#45).

  Minimal ClojureScript + React entry point. On init it fetches the seeded KI
  from GET /api/ki/:id, logs the result to the console, and dumps the raw data
  into the #agora-app mount point. No real UI yet — the KI display component is
  #46 and the hidden route is #47."
  (:require
   [cljs.pprint :refer [pprint]]
   [re-frame.core :as rf]
   [reagent.dom :as rdom]
   [superstructor.re-frame.fetch-fx]))

(goog-define ENV "dev")

(def seeded-ki-id
  "The KI seeded in #40; hardcoded here until navigation/routing exists."
  "00000000-0000-0000-0000-000000000001")

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(rf/reg-event-db ::init-db (fn [_ _] {:status :loading :ki nil :error nil}))

(rf/reg-event-fx
 ::fetch-ki
 (fn [{:keys [db]} [_ id]]
   {:db (assoc db :status :loading)
    ;; :response-content-types makes fetch-fx parse the JSON body into data
    ;; (otherwise :body arrives as a raw text string).
    :fetch {:method :get
            :url (str "/api/ki/" id)
            :headers {"Accept" "application/json"}
            :response-content-types {#"application/json" :json}
            :on-success [::fetch-ki-ok]
            :on-failure [::fetch-ki-failed]}}))

(rf/reg-event-db
 ::fetch-ki-ok
 (fn [db [_ response]]
   (let [ki (js->clj (:body response) :keywordize-keys true)]
     (js/console.log "[agora] KI fetched:" (clj->js ki))
     (assoc db :status :loaded :ki ki))))

(rf/reg-event-db
 ::fetch-ki-failed
 (fn [db [_ response]]
   (js/console.error "[agora] KI fetch failed:" (clj->js response))
   (assoc db :status :failed :error response)))

(rf/reg-sub ::status (fn [db _] (:status db)))
(rf/reg-sub ::ki (fn [db _] (:ki db)))
(rf/reg-sub ::error (fn [db _] (:error db)))

;; ---------------------------------------------------------------------------
;; Raw view (no real UI — just proves the data arrived)
;; ---------------------------------------------------------------------------

(defn raw-view
  []
  (let [status @(rf/subscribe [::status])
        ki @(rf/subscribe [::ki])
        error @(rf/subscribe [::error])]
    [:pre {:style {:font-family "monospace"
                   :font-size "0.9em"
                   :white-space "pre-wrap"
                   :background "#f5f5f5"
                   :padding "1em"
                   :border-left "3px solid #b9770e"}}
     (str "status: " (name status) "\n\n"
          (with-out-str (pprint (or ki error {}))))]))

(defn ^:dev/after-load mount-root
  []
  (rf/clear-subscription-cache!)
  (when-let [el (.getElementById js/document "agora-app")]
    (rdom/unmount-component-at-node el)
    (rdom/render [raw-view] el)))

(defn init
  []
  (js/console.log "[agora] frontend started")
  (rf/dispatch-sync [::init-db])
  (rf/dispatch [::fetch-ki seeded-ki-id])
  (mount-root))
