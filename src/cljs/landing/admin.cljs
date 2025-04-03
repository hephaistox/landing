(ns landing.admin
  "Start point for frontend"
  (:require
   [auto-core.schema          :refer [validate-data-humanize]]
   [auto-web.components.badge :refer [cinvalid cunknown cvalid]]
   [auto-web.components.img   :refer [img-schema]]
   [clojure.string            :as str]
   [landing.checkings.dics    :refer [validate-dics]]
   [landing.checkings.images  :refer [images-to-check]]
   [landing.checkings.links   :refer [tlds]]
   [re-frame.core             :refer [clear-subscription-cache!
                                      dispatch
                                      dispatch-sync
                                      reg-event-db
                                      reg-event-fx
                                      reg-sub
                                      subscribe]]
   [reagent.dom               :as rdom]
   [superstructor.re-frame.fetch-fx]))

;; ********************************************************************************
;; Parameters

(def default-db {})

(def pings
  [{:link-id :test-prod-https-com
    :url "https://hephaistox.com"}
   {:link-id :test-prod-https-fr
    :url "https://hephaistox.fr"}
   {:link-id :test-prod-http-com
    :url "http://hephaistox.com"}
   {:link-id :test-prod-http-fr
    :url "http://hephaistox.fr"}
   {:link-id :test-prod-https-com-www
    :url "https://www.hephaistox.com"}
   {:link-id :test-prod-https-fr-www
    :url "https://www.hephaistox.fr"}
   {:link-id :test-prod-http-com-www
    :url "http://www.hephaistox.com"}
   {:link-id :test-prod-http-fr-www
    :url "http://www.hephaistox.fr"}
   {:link-id :test-current
    :url "http://localhost:8080"}])

;; ********************************************************************************
;; Events

(reg-event-db ::initialize-db (fn [_ _] default-db))

(reg-event-fx ::set-modal-text (fn [{:keys [db]} [_ text]] {:db (assoc db ::modal-text text)}))

(reg-sub ::modal-text (fn [db _] (::modal-text db)))

(reg-event-fx ::do-ping
              (fn [{:keys [db]} [_ k-path url]]
                {:fetch {:method :get
                         :url url
                         :mode :cors
                         :response-content-types {#"application/.*json" :text}
                         :timeout 3000
                         :on-success [::on-ping-response k-path :success]
                         :on-failure [::on-ping-response k-path :failure]}
                 :db (assoc-in db k-path {:status :in-progress})}))

(reg-event-fx ::on-ping-response
              (fn [{:keys [db]} [_ k-path status res]]
                {:db (update-in db k-path assoc :status status :res res)}))

(reg-sub ::ping-response (fn [db [_ k-path]] (get-in db k-path)))

;; ********************************************************************************
;; Page

(defn- badge
  [opt & p]
  (let [opt* (if (map? opt) opt {})
        opt* (update opt* :class #(str % " "))]
    (case (if (map? opt) p opt)
      :failure (cinvalid opt*)
      :success (cvalid opt*)
      (cunknown opt*))))

(defn- tag
  [opt & contents]
  (let [opt* (if (map? opt) opt {})
        contents* (if (map? opt) contents (concat [opt] contents))]
    (into [:div
           (-> opt*
               (update :class #(str % " w3-tag w3-padding w3-light-grey w3-small w3-flex"))
               (update :style assoc :gap "8px"))]
          contents*)))

(defn- tease-details
  [opt & modal-content]
  (let [opt* (if (map? opt) opt {})
        modal-text* (if (map? opt) (first modal-content) opt)]
    [:div {:on-click #(dispatch [::set-modal-text modal-text*])
           :style {:visibility (if (str/blank? modal-text*) "hidden" "visible")
                   :padding "0px 2px 0px 2px"}}
     [:i.fa.fa-ellipsis-v opt*]]))

(defn- modal
  [modal-content]
  [:div#modal-details.w3-modal {:style {:display (if modal-content "block" "none")}}
   [:div.w3-modal-content.w3-card.w3-padding {:on-click #(dispatch [::set-modal-text nil])}
    [:h1 "Details"]
    [:hr]
    modal-content]])

(defn admin-rf-body
  []
  [:div.w3-xlarge.w3-panel
   (modal @(subscribe [::modal-text]))
   [:h2 "Domain, TLD and protocols"]
   (into [:div.w3-flex {:style {:flex-wrap "wrap"
                                :gap "8px"}}]
         (for [{:keys [url link-id]} pings]
           (let [{:keys [status res]} @(subscribe [::ping-response [:domain-check link-id]])]
             [tag {:key link-id
                   :on-click #(dispatch [::do-ping [:domain-check link-id] url])
                   :style {:cursor "pointer"}}
              [:div url]
              [badge status]
              [tease-details (:problem-message res)]])))
   [:h2 "Images"]
   (into [:div.w3-flex {:style {:flex-wrap "wrap"
                                :gap "8px"}}]
         (for [{:keys [url id alt]
                :as img}
               (sort-by :url images-to-check)]
           (let [{:keys [status res]} @(subscribe [::ping-response [:images id]])
                 img-schema-error (validate-data-humanize (img-schema img) img)]
             [tag {:key id
                   :style {:cursor "pointer"}
                   :on-click #(dispatch [::do-ping [:images id] url])}
              [:div alt]
              [badge (if img-schema-error :failure :success)]
              [tease-details {}
               img-schema-error]
              [badge status]
              [tease-details [:div (:problem-message res) [:img.w3-image {:src url}]]]])))
   [:h2 "Dictionnary"]
   (into [:div]
         (for [[from dic-by-from] (group-by :from validate-dics)]
           [:div.w3-row
            [:h3 from]
            [:div.w3-flex {:style {:flex-wrap "wrap"
                                   :gap "8px"}}
             (for [dic-entry (sort-by (comp str :link-id) dic-by-from)]
               (let [{:keys [tests id]} dic-entry
                     valid-langs? (and (= :valid (:missing-languages tests))
                                       (= :valid (:unexpected-languages tests)))]
                 [tag {:key id
                       :on-click #(dispatch [::set-modal-text
                                             [:div.w3-small
                                              [:h2 "Dictionary entry"]
                                              [:p "`" id "` from `" from "`"]
                                              [:h2 "Missing languages:"]
                                              [:p (:missing-languages tests)]
                                              [:h2 "Unexpected languages:"]
                                              [:p (:unexpected-languages tests)]
                                              [:h2 "Dic entry"]
                                              [:p (str dic-entry)]]])}
                  id
                  (badge (if valid-langs? :success :failure))
                  [:i.fa.fa-ellipsis-v {:class (when-not valid-langs? "w3-hide")}]]))]]))
   [:h2 "TLD"]
   (into [:div.w3-flex {:style {:flex-wrap "wrap"
                                :gap "8px"}}]
         (for [[tld url] tlds]
           (let [{:keys [status res]} @(subscribe [::ping-response [:tld tld]])]
             [tag {:key tld
                   :on-click #(dispatch [::do-ping [:tld tld] url])}
              tld
              " "
              [badge status]
              [tease-details (:problem-message res)]])))])

;; ********************************************************************************

(defn ^:dev/after-load mount-root
  []
  (clear-subscription-cache!)
  (let [root-el (.getElementById js/document "admin-panel")]
    (rdom/unmount-component-at-node root-el)
    (rdom/render [admin-rf-body] root-el)))

(defn init
  []
  (js/console.log "Landing frontend started")
  (dispatch-sync [::initialize-db])
  (mount-root))
