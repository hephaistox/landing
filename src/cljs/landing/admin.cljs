(ns landing.admin
  "Start point for frontend"
  (:require
   [cljs.pprint         :refer [pprint]]
   [landing.article.rivalis]
   [landing.pages.admin :refer [links w3c-validate-css w3c-validate-htmls]]
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

;; ********************************************************************************
;; Environment dependant values
;; ********************************************************************************

(goog-define ENV "dev")

;; ********************************************************************************
;; Parameters
;; ********************************************************************************

(def default-db {::show-valid true})

(reg-event-db ::initialize-db (fn [_ _] default-db))

;; ********************************************************************************
;; show-valid
;; ********************************************************************************
(reg-sub ::show-valid (fn [db _] (::show-valid db)))
(reg-event-fx ::set-show-valid (fn [{:keys [db]} [_]] {:db (update db ::show-valid not)}))

;; ********************************************************************************
;; links
;; ********************************************************************************
(def main-apps-entry
  (merge {}
         (when (= "dev" ENV)
           {"Shadow-cljs dashboard" "http://localhost:9551/dashboard"
            "Browser test" "http://localhost:9651/"})))

(defn- link-hiccup
  [name-app-entry url]
  [:div.w3-hover-grey.w3-padding {:key name-app-entry}
   [:a {:href url
        :target "blank"}
    name-app-entry]])

(defn- card
  [title & content]
  [:div {:style {:width "25%"}}
   [:div.w3-cell [:div.w3-card-4 [:header.w3-container.w3-grey.w3-bold title] [:div content]]]])

(defn card-with-link
  [title links]
  (card title
        (->> links
             (partition 2)
             (map #(apply link-hiccup %)))))

(defn links-hiccup
  []
  [:div.w3-flex.w3-small {:style {:gap "0.5em"
                                  :width "100%"
                                  :flex-wrap "wrap"}}
   (card-with-link "Github"
                   ["Landing"
                    "https://github.com/hephaistox/landing"
                    "Project"
                    "https://github.com/orgs/hephaistox/projects/1/views/3"])
   (card-with-link "Features"
                   ["Main Page"
                    "/"
                    "Shadow-cljs repl"
                    "http://localhost:9551/dashboard"
                    "Admin"
                    "/all-kind-of-checks"
                    "Swagger"
                    "/api/api-docs/"
                    "Exception"
                    "/exception"
                    "404"
                    "/non-existing-page"])
   (card-with-link
    "Environments"
    ["Local acceptance"
     "https://app-77d00968-72be-45d9-a5d0-cd48de6f0bcf.cleverapps.io/all-kind-of-checks"
     "Production"
     "https://app-310e3757-812b-4d7a-bd70-a58cfc181505.cleverapps.io/all-kind-of-checks"
     (if (= "dev" ENV) [:u [:b "Local env"]] "Local env")
     "http://localhost:8080"])])

;; ********************************************************************************
;; Ping urls
;; ********************************************************************************

(defn- to-absolute-url
  "If `url` is already absolute, returns it unchanged.
  Otherwise, use the browser to resolve it relative to the current location."
  [url]
  (-> (try (let [abs-url (js/URL. url js/window.location.href)] (.-href abs-url))
           (catch :default _ url))
      js/encodeURIComponent))

(reg-event-fx ::do-check-url
              (fn [{:keys [db]} [_ origin link-id]]
                {:fetch {:method :get
                         :url (str "check-url?link-id=" (name link-id)
                                   "&origin=" origin
                                   "&domain=" (to-absolute-url "/"))
                         :mode :no-cors
                         :timeout 3000
                         :on-success [::on-ping-response origin link-id :success]
                         :on-failure [::on-ping-response origin link-id :failure]}
                 :db (assoc-in db [:check-url-status origin link-id] {:status :in-progress})}))

(doseq [{:keys [link-id origin]} links] (dispatch [::do-check-url origin link-id]))

(reg-event-fx ::on-ping-response
              (fn [{:keys [db]} [_ origin link-id status res]]
                {:db
                 (update-in db [:check-url-status origin link-id] assoc :status status :res res)}))

(reg-sub ::ping-response
         (fn [db [_ origin link-id]] (get-in db [:check-url-status origin link-id])))

(comment
  (to-absolute-url "/zea")
  (group-by :origin links)
  ;(dispatch [::do-check-url id link-id origin])
  ;;
)

(defn- ping-card
  [show-valid {:keys [link-id url origin]}]
  (let [id (str (name origin) "/" (name link-id))
        {:keys [status res]} @(subscribe [::ping-response origin link-id])]
    (when (or (= :failure status) (= :in-progress status) (not show-valid))
      [:a {:href url
           :target "blank"
           :id id
           :key id}
       [:div.w3-tooltip.w3-small.w3-padding-small {:class (case status
                                                            :success "w3-green"
                                                            :failure "w3-red"
                                                            :in-progress "w3-grey"
                                                            "w3-black")}
        (name link-id)
        [:div.w3-text.w3-grey.w3-padding {:style {:position "absolute"
                                                  :left "0px"
                                                  :z-index 9999
                                                  :width "500px"
                                                  :top "2.5em"}}
         [:table
          [:tbody
           [:tr [:td.w3-bold "origin"] [:td origin]]
           [:tr [:td.w3-bold "link-id"] [:td link-id]]
           [:tr [:td.w3-bold "domain"] [:td (to-absolute-url "/")]]
           [:tr [:td.w3-bold "status"] [:td status]]
           [:tr [:td.w3-bold "link"] [:td url]]
           [:tr [:td.w3-bold "result"] [:td [:pre (with-out-str (pprint res))]]]]]]]])))

(defn ping-card-by-origin
  [show-valid title links]
  [[:header.w3-container.w3-grey.w3-bold title] (doall (map (partial ping-card show-valid) links))])

(defn domain-tld-hiccup
  []
  (let [show-valid @(subscribe [::show-valid])]
    (-> [:div.w3-flex {:style {:gap "0.5em"
                               :align-content "center"
                               :width "100%"
                               :flex-wrap "wrap"}}]
        (concat (mapcat (fn [[origin links]] (ping-card-by-origin show-valid origin links))
                 (group-by :origin links)))
        vec)))

;; ********************************************************************************
;; Validation
;; ********************************************************************************

(defn validation-event
  [kw]
  (fn [{:keys [db]} [_ id url]]
    {:fetch {:method :get
             :url "w3c-validate"
             :headers {"Accept" "application/json"}
             :mode :cors
             :params {kw (name id)
                      :domain "https://hephaistox.fr"}
             :timeout 3000
             :redirect :follow
             :on-success [::on-validation-response id :success]
             :on-failure [::on-validation-response id :failure]}
     :db (assoc-in db
          [::validation-response id]
          {:status :in-progress
           :url url})}))

(reg-event-fx ::do-css-validation (validation-event :css-id))
(reg-event-fx ::do-html-validation (validation-event :html-id))

(reg-sub ::validation-response (fn [db [_ id]] (get (::validation-response db) id)))

(reg-event-fx ::on-validation-response
              (fn [{:keys [db]} [_ css-id status res]]
                {:db (update-in db [::validation-response css-id] assoc :status status :res res)}))

(doseq [[css-id css-url] w3c-validate-css] (dispatch [::do-css-validation css-id css-url]))
(doseq [[html-id html-url] w3c-validate-htmls] (dispatch [::do-html-validation html-id html-url]))

(comment
  (dispatch [::do-css-validation (ffirst w3c-validate-css) (second (first w3c-validate-css))])
  (first @(subscribe [::validation-response]))
  ;
)

(defn- validations
  [ids show-valid validation-url]
  (doall
   (for [id ids]
     (let [{:keys [status res url]} @(subscribe [::validation-response id])]
       (when (or (= :failure status) (= :in-progress status) (not show-valid))
         [:div.w3-cell.w3-tooltip.w3-small {:key (str "css/" id)}
          [:div.w3-flex.w3-padding {:class (case status
                                             :in-progress "w3-grey"
                                             :success "w3-green"
                                             :failure "w3-red"
                                             "w3-grey")}
           [:a {:href (str validation-url url)
                :target "blank"}
            (name id)]]
          [:div.w3-text.w3-grey.w3-padding {:style {:position "absolute"
                                                    :left "0px"
                                                    :z-index 9999
                                                    :top "2.5em"}}
           [:table
            [:tbody
             [:tr [:td.w3-bold "id"] [:td id]]
             [:tr [:td.w3-bold "status"] [:td status]]
             [:tr [:td.w3-bold "link"] [:td url]]
             [:tr [:td.w3-bold "result"] [:td [:pre (with-out-str (pprint res))]]]]]]])))))

(defn validation-hiccup
  []
  (let [show-valid @(subscribe [::show-valid])]
    [:div.w3-flex {:style {:flex-direction "column"
                           :gap "1em"}}
     [:h2 "css"]
     [:div.w3-flex {:style {:gap "0.4em"}}
      (validations (keys w3c-validate-css)
                   show-valid
                   "https://jigsaw.w3.org/css-validator/validator?uri=https://hephaistox.fr/")]
     [:h2 "html"]
     [:div.w3-flex {:style {:gap "0.4em"}}
      (validations (keys w3c-validate-htmls)
                   show-valid
                   "https://validator.w3.org/nu/?doc=https://hephaistox.fr/")]]))

;; ********************************************************************************
;; Page

(defn admin-rf-body
  []
  (let [show-valid @(subscribe [::show-valid])]
    [:div.w3-xlarge.w3-panel.text
     [:div {:style {:position "sticky"
                    :top "0px"}}
      [:div.w3-flex.w3-padding {:style {:position "absolute"
                                        :cursor "pointer"
                                        :right "0"}}
       [:div.w3-card.w3-small.w3-padding.w3-cursor {:on-click #(dispatch [::set-show-valid
                                                                          [:show-valid]])}
        (if show-valid "Show all" "Show invalid only")]]]
     [:h1 "Administration page"]
     [:h1 "Links"]
     (links-hiccup)
     [:h1 "Domain, TLD and protocols"]
     (domain-tld-hiccup)
     [:h1 "Validation"]
     (validation-hiccup)]))

;; ********************************************************************************

(defn adjust-tooltip
  [^js tooltip]
  (let [rect (.getBoundingClientRect tooltip)
        win-w (.-innerWidth js/window)
        win-h (.-innerHeight js/window)
        style (.-style tooltip)]
    ;; --- Horizontal handling ---
    (when (> (.-right rect) win-w) (set! (.-left style) (str (- win-w (.-right rect) -5) "px")))
    (when (< (.-left rect) 0) (set! (.-left style) (str (+ (- (.-left rect)) 5) "px")))
    ;; --- Vertical handling ---
    (when (> (.-bottom rect) win-h)
      ;; Flip above
      (set! (.-top style) "auto")
      (set! (.-bottom style) "2.5em"))))

(defn reset-tooltip
  [^js tooltip original]
  (let [style (.-style tooltip)]
    ;; Restore original top/bottom
    (set! (.-top style) (:top original))
    (set! (.-bottom style) (:bottom original))
    ;; Restore original horizontal position
    (set! (.-left style) (:left original))
    (set! (.-right style) (:right original))))

(defn init-tooltips!
  []
  (doseq [container (array-seq (.querySelectorAll js/document ".w3-tooltip"))]
    (let [tooltip (.querySelector container ".w3-text")
          ;; Store original inline values
          original {:top (.-top (.-style tooltip))
                    :bottom (.-bottom (.-style tooltip))
                    :right (.-right (.-style tooltip))
                    :left (.-left (.-style tooltip))}]
      (.addEventListener container "mouseenter" (fn [_] (adjust-tooltip tooltip)))
      (.addEventListener container "mouseleave" (fn [_] (reset-tooltip tooltip original))))))

;; ********************************************************************************

(defn ^:dev/after-load mount-root
  []
  (clear-subscription-cache!)
  (let [root-el (.getElementById js/document "admin-panel")]
    (rdom/unmount-component-at-node root-el)
    (rdom/render [admin-rf-body] root-el)))

(defn init
  []
  (js/console.log "Landing admin started")
  (dispatch-sync [::initialize-db])
  (mount-root)
  (.addEventListener js/document "DOMContentLoaded" init-tooltips!))
