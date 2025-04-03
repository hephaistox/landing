(ns landing.fe
  "Start point for frontend"
  (:require
   [re-frame.core :as re-frame]
   [reagent.dom   :as rdom]))

(def default-db {})

(re-frame/reg-event-db ::initialize-db (fn [_ _] default-db))

(defn ^:dev/after-load mount-root
  []
  (re-frame/clear-subscription-cache!)
  (if-let [root-el (.getElementById js/document "app-simulation")]
    (do (rdom/unmount-component-at-node root-el) (rdom/render [:p "ZAE"] root-el))
    (js/console.error "Missing app id element in the page")))

(defn init
  []
  (js/console.log "Landing frontend started")
  (re-frame/dispatch-sync [::initialize-db])
  (mount-root))
