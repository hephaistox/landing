(ns landing.js-example
  (:require
   [auto-js.sim.js       :as sim-js]
   [auto-js.start        :refer [data]]
   [auto-sim.canvas      :refer [layout sprites]]
   [auto-sim.control-bar :refer [simulation-control-bar]]
   [auto-sim.links       :refer [links]]
   [auto-sim.modal       :refer [simulation-control-panel]]
   [auto-web.lang-fe     :refer [clang]]
   [re-frame.core        :refer [clear-subscription-cache! dispatch-sync reg-event-db subscribe]]
   [reagent.dom          :as rdom]))

(def rendering-data
  (->> {:product {:x [0 "em"]
                  :y [1 "em"]
                  :sprite :source}
        :m1 {:x [10 "em"]
             :y [0 "em"]
             :input [:p1 :p2]
             :output [:p3 :p4]
             :sprite :machine}
        :m2 {:x [40 "em"]
             :y [0 "em"]
             :sprite :machine}
        :m3 {:x [10 "em"]
             :y [10 "em"]
             :sprite :machine}
        :sink {:x [50 "em"]
               :y [5 "em"]
               :sprite :sink}}
       (map (fn [[k v]] [k (assoc v :rendering-id k)]))
       (into {})))

(defn update-data-with-rendering
  [data rendering-data]
  (-> data
      (update :resources
              update-vals
              (fn [resource] (merge resource (get rendering-data (:resource-id resource)))))))

(def model
  (-> data
      sim-js/prepare
      (update-data-with-rendering rendering-data)
      (sim-js/run 30000)))

(reduce (fn [rendering-data
             {:keys [rendering-id output]
              :as _machine}]
          (assoc-in rendering-data [rendering-id :output] output))
        rendering-data
        (vals (:resources model)))

;; ********************************************************************************
;; Mounting

(defn js-example-body
  []
  (let [l (clang)]
    [:<>
     [:div.w3-col.w3-light-grey {:style {:flex-grow "1"}}
      [:div.w3-flex.w3-panel
       [:div {:style {:flex-grow "1"}}]
       [:div
        [simulation-control-bar {:class "w3-row"}
         l]]
       [:div {:style {:flex-grow "1"}}]]
      [layout rendering-data sprites links]]
     [:div
      (if @(subscribe [:auto-sim.modal/modal-close?])
        [:hr {:style {:margin "0px 3px 0px 3px"
                      :height "10px"}}]
        [:div.w3-center [:i.fa.fa-angleup]])
      [simulation-control-panel {:class "w3-background-white"}]]]))

(defn ^:dev/after-load mount-root
  []
  (clear-subscription-cache!)
  (let [root-el (.getElementById js/document "js-example-content")]
    (rdom/unmount-component-at-node root-el)
    (rdom/render [js-example-body] root-el)))

(reg-event-db ::initialize-db (fn [_ _] {}))

(defn init
  []
  (js/console.log "Landing frontend started")
  (dispatch-sync [::initialize-db])
  (mount-root))
