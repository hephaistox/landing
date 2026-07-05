(ns landing.agora.frontend.auth
  "Auth UI + state (#38). Header shows Log in / Register when logged out, and a
  profile avatar with a Log out dropdown when logged in. A modal handles the
  email/password login and registration. Session is a server cookie; the current
  user is discovered via GET /agora/api/auth/me on load."
  (:require
   [clojure.string              :as str]
   [landing.agora.frontend.i18n :as i18n]
   [re-frame.core               :as rf]
   [superstructor.re-frame.fetch-fx]))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(rf/reg-sub ::user (fn [db _] (::user db)))
(rf/reg-sub ::form (fn [db _] (::form db)))
(rf/reg-sub ::menu? (fn [db _] (::menu? db)))

(defn logged-in?
  "True when a user is in app-db. Usable from other namespaces via the sub."
  [db]
  (some? (::user db)))

(defn- json-req
  [method url body on-success on-failure]
  {:method method
   :url url
   :headers {"Content-Type" "application/json"
             "Accept" "application/json"}
   :body (js/JSON.stringify (clj->js body))
   :response-content-types {#"application/json" :json}
   :on-success on-success
   :on-failure on-failure})

(rf/reg-event-fx ::check
                 (fn [_ _]
                   {:fetch {:method :get
                            :url "/agora/api/auth/me"
                            :headers {"Accept" "application/json"}
                            :response-content-types {#"application/json" :json}
                            :on-success [::me-ok]
                            :on-failure [::me-ok]}}))

(rf/reg-event-fx ::me-ok
                 (fn [{:keys [db]} [_ resp]]
                   (let [user (:body resp)]
                     (cond-> {:db (assoc db ::user user)}
                       ;; adopt the account's saved interface-language preference
                       (:lang user) (assoc :dispatch [:agora/adopt-lang (:lang user)])))))

(rf/reg-event-db ::open
                 (fn [db [_ mode]]
                   (assoc db
                          ::form
                          {:mode mode
                           :email ""
                           :password ""
                           :display-name ""
                           :error nil
                           :submitting? false})))

(rf/reg-event-db ::close (fn [db _] (dissoc db ::form)))
(rf/reg-event-db ::set (fn [db [_ k v]] (assoc-in db [::form k] v)))
(rf/reg-event-db ::switch
                 (fn [db [_ mode]]
                   (update db
                           ::form
                           merge
                           {:mode mode
                            :error nil})))

(rf/reg-event-fx ::submit
                 (fn [{:keys [db]} _]
                   (let [{:keys [mode email password display-name]} (::form db)
                         url (if (= mode :login) "/agora/api/auth/login" "/agora/api/auth/register")
                         body (cond-> {:email email
                                       :password password}
                                (= mode :register) (assoc :display-name display-name))]
                     {:db (assoc-in db [::form :submitting?] true)
                      :fetch (json-req :post url body [::auth-ok] [::auth-failed])})))

(rf/reg-event-db ::auth-ok
                 (fn [db [_ resp]]
                   (-> db
                       (assoc ::user (:body resp))
                       (dissoc ::form ::menu?))))

(rf/reg-event-db ::auth-failed
                 (fn [db [_ resp]]
                   (update db
                           ::form
                           merge
                           {:submitting? false
                            :error (or (get-in resp [:body :error]) "Something went wrong")})))

(rf/reg-event-db ::toggle-menu (fn [db _] (update db ::menu? not)))

(rf/reg-event-fx ::logout
                 (fn [_ _]
                   {:fetch {:method :post
                            :url "/agora/api/auth/logout"
                            :headers {"Accept" "application/json"}
                            :on-success [::logged-out]
                            :on-failure [::logged-out]}}))

(rf/reg-event-db ::logged-out (fn [db _] (dissoc db ::user ::menu?)))

;; ---------------------------------------------------------------------------
;; View
;; ---------------------------------------------------------------------------

(defn- initials
  [nm]
  (->> (str/split (or nm "") #"\s+")
       (remove str/blank?)
       (map first)
       (take 2)
       (apply str)
       str/upper-case))

(def ^:private header-btn
  {:font-size "0.85em"
   :padding "0.35em 0.8em"
   :border "1px solid #b9770e"
   :border-radius "0.3em"
   :background "transparent"
   :color "#e8e2d6"
   :cursor "pointer"})

(defn auth-controls
  "Header right side: Log in / Register buttons, or the profile avatar + menu."
  []
  (let [user @(rf/subscribe [::user])
        menu? @(rf/subscribe [::menu?])
        lang @(rf/subscribe [::i18n/lang])]
    (if user
      [:div {:style {:position "relative"}}
       [:button {:on-click #(rf/dispatch [::toggle-menu])
                 :title (:display-name user)
                 :style {:width "2.2em"
                         :height "2.2em"
                         :padding 0
                         :overflow "hidden"
                         :border-radius "50%"
                         :border "1px solid #d99a2b"
                         :background "#b9770e"
                         :color "#fff"
                         :font-weight 700
                         :font-size "0.85em"
                         :cursor "pointer"}}
        (if-let [avatar (:avatar-url user)]
          [:img {:src avatar
                 :alt (:display-name user)
                 :referrer-policy "no-referrer"
                 :style {:width "100%"
                         :height "100%"
                         :object-fit "cover"
                         :display "block"}}]
          (initials (:display-name user)))]
       (when menu?
         [:div {:style {:position "absolute"
                        :right 0
                        :top "2.7em"
                        :z-index 30
                        :min-width "12em"
                        :background "#fff"
                        :color "#222"
                        :border "1px solid #ddd"
                        :border-radius "0.4em"
                        :box-shadow "0 4px 12px rgba(0,0,0,0.15)"
                        :padding "0.6em"}}
          [:div {:style {:padding "0.2em 0.3em 0.5em"
                         :border-bottom "1px solid #eee"
                         :margin-bottom "0.5em"}}
           [:div {:style {:font-weight 700}}
            (:display-name user)]
           (when (:email user)
             [:div {:style {:font-size "0.8em"
                            :color "#888"}}
              (:email user)])]
          [:a {:href (i18n/preferences lang)
               :on-click #(rf/dispatch [::toggle-menu])
               :style {:display "block"
                       :padding "0.4em"
                       :margin-bottom "0.4em"
                       :border "1px solid #ccc"
                       :border-radius "0.3em"
                       :background "#fff"
                       :color "#333"
                       :text-align "center"
                       :text-decoration "none"}}
           (i18n/t lang :nav/preferences)]
          [:button {:on-click #(rf/dispatch [::logout])
                    :style {:width "100%"
                            :padding "0.4em"
                            :border "1px solid #ccc"
                            :border-radius "0.3em"
                            :background "#fff"
                            :cursor "pointer"}}
           (i18n/t lang :auth/logout)]])]
      [:div {:style {:display "flex"
                     :gap "0.5em"}}
       [:button {:on-click #(rf/dispatch [::open :login])
                 :style header-btn}
        (i18n/t lang :auth/login)]
       [:button {:on-click #(rf/dispatch [::open :register])
                 :style (assoc header-btn :background "#b9770e" :color "#fff")}
        (i18n/t lang :auth/register)]])))

(defn- field
  [label type value on-change]
  [:label {:style {:display "block"
                   :margin-bottom "0.7em"}}
   [:div {:style {:font-size "0.8em"
                  :color "#555"
                  :margin-bottom "0.2em"}}
    label]
   [:input {:type type
            :value value
            :on-change on-change
            :style {:width "100%"
                    :box-sizing "border-box"
                    :padding "0.5em"
                    :font-size "0.95em"
                    :border "1px solid #ccc"
                    :border-radius "0.3em"}}]])

(defn auth-modal
  "Login / registration overlay, shown when a form is open."
  []
  (when-let [{:keys [mode email password display-name error submitting?]} @(rf/subscribe [::form])]
    (let [register? (= mode :register)
          lang @(rf/subscribe [::i18n/lang])]
      [:div {:on-click #(rf/dispatch [::close])
             :style {:position "fixed"
                     :inset 0
                     :z-index 100
                     :background "rgba(0,0,0,0.45)"
                     :display "flex"
                     :align-items "flex-start"
                     :justify-content "center"
                     :padding-top "10vh"}}
       [:div {:on-click #(.stopPropagation %)
              :style {:width "22em"
                      :max-width "90%"
                      :background "#fff"
                      :border-radius "0.6em"
                      :padding "1.4em"
                      :font-family "system-ui, sans-serif"}}
        [:h2 {:style {:margin "0 0 0.8em"
                      :font-size "1.3em"}}
         (i18n/t lang (if register? :auth/create-account :auth/login))]
        [:a {:href "/agora/api/auth/google"
             :style {:display "block"
                     :text-align "center"
                     :padding "0.55em"
                     :border "1px solid #ccc"
                     :border-radius "0.3em"
                     :text-decoration "none"
                     :color "#333"
                     :font-weight 600
                     :margin-bottom "0.9em"}}
         (i18n/t lang :auth/google)]
        [:div {:style {:text-align "center"
                       :color "#aaa"
                       :font-size "0.8em"
                       :margin-bottom "0.9em"}}
         (i18n/t lang :auth/or)]
        [field
         (i18n/t lang :auth/email)
         "email"
         email
         #(rf/dispatch [::set :email (.. % -target -value)])]
        [field
         (i18n/t lang :auth/password)
         "password"
         password
         #(rf/dispatch [::set :password (.. % -target -value)])]
        (when register?
          [field
           (i18n/t lang :auth/alias)
           "text"
           display-name
           #(rf/dispatch [::set :display-name (.. % -target -value)])])
        (when error
          [:div {:style {:color "#c92a2a"
                         :font-size "0.85em"
                         :margin-bottom "0.6em"}}
           error])
        [:button {:on-click #(rf/dispatch [::submit])
                  :disabled (boolean submitting?)
                  :style {:width "100%"
                          :padding "0.55em"
                          :border "none"
                          :background "#b9770e"
                          :color "#fff"
                          :border-radius "0.3em"
                          :font-size "1em"
                          :cursor (if submitting? "default" "pointer")}}
         (if submitting? "…" (i18n/t lang (if register? :auth/register :auth/login)))]
        [:div {:style {:text-align "center"
                       :margin-top "0.8em"
                       :font-size "0.85em"
                       :color "#666"}}
         (i18n/t lang (if register? :auth/have-account :auth/new-here))
         [:a {:href "#"
              :on-click
              (fn [e] (.preventDefault e) (rf/dispatch [::switch (if register? :login :register)]))
              :style {:color "#b9770e"}}
          (i18n/t lang (if register? :auth/login :auth/register))]]]])))
