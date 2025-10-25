(ns landing.admin
  "Start point for frontend"
  (:require
   [auto-core.schema    :refer [validate-data-humanize]]
   [landing.article.digital-twin]
   [landing.article.hephaistox]
   [landing.article.project]
   [landing.article.rivalis]
   [landing.article.who-are-we]
   [landing.pages.admin :refer [dics images links]]
   [landing.pages.home]
   [landing.pages.structure]
   [landing.routes]
   [re-frame.core       :refer [clear-subscription-cache!
                                dispatch
                                dispatch-sync
                                reg-event-db
                                reg-event-fx
                                reg-sub
                                subscribe]]
   [reagent.dom         :as rdom]
   [superstructor.re-frame.fetch-fx]))

(goog-define ENV "dev")
(if (= "dev" ENV)
  (do (def pings-env
        [{:link-id :local
          :url "http://localhost:8080"}])
      (def main-apps-entry-env
        {"Shadow-cljs dashboard" "http://localhost:9551/dashboard"
         "Browser test" "http://localhost:9651/"}))
  (do (def pings-env []) (def main-apps-entry-env {})))

;; ********************************************************************************
;; Parameters

(def default-db {::show-valid true})

;; ********************************************************************************
;; Events

(defn- to-absolute-url
  [url]
  ;; If `url` is already absolute, return it unchanged.
  ;; Otherwise, use the browser to resolve it relative to the current location.
  (try (let [abs-url (js/URL. url js/window.location.href)] (.-href abs-url))
       (catch :default _ url)))

(comment
  (to-absolute-url "/zea")
  ;;
)

(reg-event-db ::initialize-db (fn [_ _] default-db))

(reg-event-fx ::set-modal-text (fn [{:keys [db]} [_ text]] {:db (assoc db ::modal-text text)}))
(reg-sub ::modal-text (fn [db _] (::modal-text db)))

(reg-sub ::show-valid (fn [db _] (::show-valid db)))
(reg-event-fx ::set-show-valid (fn [{:keys [db]} [_]] {:db (update db ::show-valid not)}))

(reg-event-fx ::do-ping
              (fn [{:keys [db]} [_ k-path link-id origin]]
                {:fetch {:method :get
                         :url (str "check-url?link-id=" (name link-id)
                                   "&origin=" origin
                                   "&domain=" (to-absolute-url "/"))
                         :mode :no-cors
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

(doseq [{:keys [link-id origin]} (links pings-env)]
  (let [id (str (name origin) "/" (name link-id))]
    (dispatch [::do-ping [:domain-check id] link-id origin])))

(def main-apps-entry
  (merge {"Main Page" "/"
          "Admin" "/all-kind-of-checks"
          "Swagger" "/api/api-docs/"
          "Exception" "/exception"
          "Github" "https://github.com/hephaistox/landing"
          "Local acceptance"
          "https://app-77d00968-72be-45d9-a5d0-cd48de6f0bcf.cleverapps.io/all-kind-of-checks"
          "Production"
          "https://app-310e3757-812b-4d7a-bd70-a58cfc181505.cleverapps.io/all-kind-of-checks"
          "Local env" "http://localhost:8080"}
         main-apps-entry-env))

(defn admin-rf-body
  []
  (let [show-valid @(subscribe [::show-valid])]
    [:div.w3-xlarge.w3-panel.text
     [:div.w3-flex.w3-padding {:style {:position "absolute"
                                       :right "0"}}
      [:div.w3-card.w3-small.w3-padding {:on-click #(dispatch [::set-show-valid [:show-valid]])}
       (if show-valid "Show all" "Show invalid only")]]
     [:h1 "Administration page"]
     [:h1 "Links"]
     [:div.w3-flex {:style {:gap "0.5em"
                            :flex-wrap "wrap"}}
      (doall (for [[name-app-entry url] main-apps-entry]
               [:div.w3-card.w3-padding.w3-hover-opacity {:key name-app-entry}
                [:a {:href url}
                 name-app-entry]]))]
     [:h1 "Domain, TLD and protocols"]
     [:div.w3-flex {:style {:gap "0.5em"
                            :flex-wrap "wrap"
                            :width "80%"}}
      (doall
       (for [{:keys [url link-id origin]} (links pings-env)]
         (let [id (str (name origin) "/" (name link-id))
               {:keys [status res]} @(subscribe [::ping-response [:domain-check id]])]
           (when (or (= :failure status) (= :in-progress status) (not show-valid))
             [:a {:href url
                  :id id
                  :key id}
              [:div.w3-tooltip.w3-small.w3-padding-small {:class (case status
                                                                   :success "w3-green"
                                                                   :failure "w3-red"
                                                                   :in-progress "w3-grey"
                                                                   "w3-black")}
               (name link-id)
               [:div.w3-text.w3-black.w3-padding {:style {:position "absolute"
                                                          :left "0px"
                                                          :z-index 9999
                                                          :width "500px"
                                                          :top "2.5em"}}
                [:ul
                 [:li "Id:" [:br] id]
                 [:li "Status:" [:br] status]
                 [:li "Link: " [:br] url]
                 [:li "Result: " [:br] res]]]]]))))]
     [:h1 "Images"]
     [:div.w3-flex {:style {:gap "0.5em"
                            :flex-wrap "wrap"
                            :width "80%"}}
      (doall
       (for [{:keys [url alt img-id origin]
              :as img-data}
             images]
         (let [id (str (name origin) "/" (name img-id))
               img-valid? (validate-data-humanize [:map {:closed true}
                                                   [:url :string]
                                                   [:alt :string]
                                                   [:origin :string]
                                                   [:img-id :keyword]]
                                                  img-data)]
           (when (or (= :failure img-valid?) (not show-valid))
             [:div.w3-tooltip.w3-small.w3-padding-small {:key id
                                                         :id id
                                                         :style (when img-valid?
                                                                  {:border-width "0.4em"
                                                                   :border-style "solid"
                                                                   :border-color "red"})}
              [:a {:href url}
               [:img {:src url
                      :width "30px"}]]
              [:div.w3-text.w3-grey.w3-padding {:style {:position "absolute"
                                                        :left "0px"
                                                        :z-index 9999
                                                        :top "2.5em"}}
               [:ul
                [:li "Id" [:br] id]
                [:li "Alt" [:br] alt]
                [:li "Link:" [:br] url]
                [:li "Img valid:" [:br] img-valid?]]]]))))]
     [:h1 "Dictionnary"]
     [:div.w3-flex {:style {:gap "0.5em"
                            :flex-wrap "wrap"
                            :width "80%"}}
      (doall
       (for [[prefix dic] dics]
         (let [dic (update-vals dic
                                (fn [dic-entry]
                                  (let [langs (set (keys dic-entry))
                                        schema-validation (validate-data-humanize [:map] dic-entry)
                                        valid-schema? (not (:error schema-validation))
                                        status (and valid-schema? (= #{:en :fr} langs))]
                                    {:langs langs
                                     :schema-validation schema-validation
                                     :status status})))
               invalid-dic (into (filter (comp not :status second) dic) {})]
           [:div.w3-card {:key prefix
                          :class (when (or show-valid (seq invalid-dic)) "w3-hide")}
            (cons [:p.w3-small.w3-center.w3-padding {:key prefix}
                   prefix]
                  (if (and (seq invalid-dic) show-valid)
                    (list [:div.w3-small {:on-click #(dispatch [::set-show-valid [:show-valid]])
                                          :style {:cursor "pointer"}}
                           "..."])
                    (doall
                     (for [[dic-entry-id {:keys [langs status schema-validation]}]
                           (if show-valid invalid-dic dic)]
                       [:div.w3-tooltip.w3-small.w3-padding-small {:key dic-entry-id
                                                                   :class
                                                                   (if status "w3-green" "w3-red")
                                                                   :id dic-entry-id}
                        dic-entry-id
                        [:div.w3-text.w3-grey.w3-padding {:style {:position "absolute"
                                                                  :left "0.4em"
                                                                  :z-index 9999
                                                                  :top "2.5em"}}
                         [:table
                          [:tr [:td "Ids"] [:td dic-entry-id]]
                          [:tr [:td "Status:"] [:td (str status)]]
                          [:tr [:td "Langs:"] [:td langs]]
                          (when schema-validation
                            [:tr [:td "Valid schema?:"] [:td schema-validation]])]]]))))])))]]))

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
