(ns landing.agora.frontend.auth
  "Auth UI + state. Header shows Log in / Register when logged out, and a
  profile avatar with a Log out dropdown when logged in. A modal handles the
  email/password login and registration. Session is a server cookie; the current
  user is discovered via GET /agora/api/auth/me on load."
  (:require
   ["altcha"] ;; registers the <altcha-widget> web component (self-hosted PoW captcha)
   ["altcha/i18n/fr-fr"] ;; registers French widget strings ($altcha.i18n "fr-fr"); must load after "altcha"
   [clojure.string                    :as str]
   [landing.agora.frontend.i18n       :as i18n]
   [landing.agora.frontend.ui-commons :as ui]
   [re-frame.core                     :as rf]
   [superstructor.re-frame.fetch-fx]))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(rf/reg-sub ::user (fn [db _] (::user db)))
(rf/reg-sub ::form (fn [db _] (::form db)))
(rf/reg-sub ::menu? (fn [db _] (::menu? db)))
;; `:admin` is derived server-side from the account email (landing.agora.auth):
;; the frontend only reflects it, gating admin UI. The API is the real boundary.
(rf/reg-sub ::admin? (fn [db _] (boolean (:admin (::user db)))))

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
                            :on-failure [::me-failed]}}))

(rf/reg-event-db ::me-failed
                 (fn [db _]
                   ;; /me couldn't be resolved (transport error, or the endpoint
                   ;; itself failed — e.g. the DB is down). Treat as anonymous rather
                   ;; than trusting the failure response body as a user (it isn't one).
                   (assoc db ::user nil)))

(rf/reg-event-fx ::me-ok
                 (fn [{:keys [db]} [_ resp]]
                   ;; a real session carries a user `:id`; an id-less body (e.g. no session) is
                   ;; logged-out, not a `{}` "user" — `logged-in?`/owner checks key off `:id`.
                   (let [user (let [u (:body resp)] (when (:id u) u))]
                     {:db (assoc db ::user user)
                      ;; adopt the account's saved interface-language preference, and drop any active
                      ;; publication that isn't this user's (a value left over from another account)
                      :fx [(when (:lang user) [:dispatch [:agora/adopt-lang (:lang user)]])
                           [:dispatch [:landing.agora.frontend.publications/reconcile-active]]]})))

(rf/reg-event-db ::open
                 (fn [db [_ mode]]
                   (assoc db
                          ::form
                          {:mode mode
                           :email ""
                           :password ""
                           :error nil
                           :submitting? false})))

(rf/reg-event-db ::close-form (fn [db _] (dissoc db ::form)))
(rf/reg-event-db ::set-form-property (fn [db [_ k v]] (assoc-in db [::form k] v)))
(rf/reg-event-db ::switch
                 (fn [db [_ mode]]
                   (update db
                           ::form
                           merge
                           {:mode mode
                            :error nil})))

(rf/reg-event-fx ::submit
                 (fn [{:keys [db]} _]
                   (let [{:keys [mode email password altcha]} (::form db)
                         url (if (= mode :login) "/agora/api/auth/login" "/agora/api/auth/register")
                         ;; no name is asked for: the account's public alias is generated
                         ;; server-side, so nothing here can carry a civil identity
                         body (cond-> {:email email
                                       :password password}
                                (= mode :register) (assoc :altcha altcha))]
                     {:db (assoc-in db [::form :submitting?] true)
                      :fetch (json-req :post url body [::auth-ok] [::auth-failed])})))

(rf/reg-event-fx ::auth-ok
                 (fn [{:keys [db]} [_ resp]]
                   {:db (-> db
                            (assoc ::user (:body resp))
                            (dissoc ::form ::menu?))
                    ;; a fresh login: drop any active publication left by another account
                    :dispatch [:landing.agora.frontend.publications/reconcile-active]}))

(rf/reg-event-db ::auth-failed
                 (fn [db [_ resp]]
                   (update db
                           ::form
                           merge
                           {:submitting? false
                            :error (or (get-in resp [:body :error]) "Something went wrong")})))

;; --- renaming the alias ----------------------------------------------------
;; The public name is rectifiable: the account holds the only copy, every byline is derived from it.
;; The form is held here, next to the user it edits, and the preferences page renders it.

(rf/reg-sub ::alias-form (fn [db _] (::alias-form db)))

(rf/reg-event-db ::edit-alias
                 (fn [db _]
                   (assoc db
                          ::alias-form
                          {:alias (:display-name (::user db))
                           :error nil
                           :submitting? false})))

(rf/reg-event-db ::close-alias-form (fn [db _] (dissoc db ::alias-form)))
(rf/reg-event-db ::set-alias-text (fn [db [_ v]] (assoc-in db [::alias-form :alias] v)))

(rf/reg-event-fx ::submit-alias
                 (fn [{:keys [db]} _]
                   {:db (assoc-in db [::alias-form :submitting?] true)
                    :fetch (json-req :post
                                     "/agora/api/auth/alias"
                                     {:alias (get-in db [::alias-form :alias])}
                                     [::alias-ok]
                                     [::alias-failed])}))

(rf/reg-event-fx ::alias-ok
                 (fn [{:keys [db]} [_ resp]]
                   {:db (-> db
                            (assoc ::user (:body resp))
                            (dissoc ::alias-form))
                    ;; every cached document carries the old byline — drop them so the next page
                    ;; shows the new name, as the server's caches were dropped on the rename
                    :dispatch [:agora/forget-documents]}))

(rf/reg-event-db ::alias-failed
                 (fn [db [_ resp]]
                   (update db
                           ::alias-form
                           merge
                           {:submitting? false
                            :error (or (get-in resp [:body :error]) "failed")})))

(rf/reg-event-db ::toggle-menu (fn [db _] (update db ::menu? not)))

(rf/reg-event-fx ::logout
                 (fn [_ _]
                   {:fetch {:method :post
                            :url "/agora/api/auth/logout"
                            :headers {"Accept" "application/json"}
                            :on-success [::logged-out]
                            :on-failure [::logged-out]}}))

(rf/reg-event-fx ::logged-out
                 (fn [{:keys [db]} _]
                   (cond-> {:db (dissoc db ::user ::menu?)
                            ;; deselect the active publication on logout — it must not carry over to
                            ;; the next account (or an anonymous session) on this browser
                            :dispatch [:landing.agora.frontend.publications/clear-active]}
                     ;; a publication page (publication / its graph) is owner-only, so once logged out
                     ;; leave it for the landing rather than sit on a now-inaccessible page
                     (contains? #{:publication :publication-graph} (:kind (:view db)))
                     (assoc :agora/navigate (i18n/home (i18n/current db))))))

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
          (let [menu-link {:display "block"
                           :padding "0.4em"
                           :margin-bottom "0.4em"
                           :border "1px solid #ccc"
                           :border-radius "0.3em"
                           :background "#fff"
                           :color "#333"
                           :text-align "center"
                           :text-decoration "none"}]
            [:<>
             [:a {:href (i18n/preferences lang)
                  :on-click #(rf/dispatch [::toggle-menu])
                  :style menu-link}
              (i18n/t lang :nav/preferences)]
             ;; Admin link only for the platform owner (server-derived :admin).
             (when (:admin user)
               [:a {:href (i18n/admin lang)
                    :on-click #(rf/dispatch [::toggle-menu])
                    :style menu-link}
                (i18n/t lang :nav/admin)])])
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
  [label type value on-text]
  [:label {:style {:display "block"
                   :margin-bottom "0.7em"}}
   [:div {:style {:font-size "0.8em"
                  :color "#555"
                  :margin-bottom "0.2em"}}
    label]
   ;; `composed-field` so dead-key / IME composition survives (e.g. an accented display name)
   [ui/composed-field {:type type
                       :value value
                       :on-text on-text
                       :style {:width "100%"
                               :box-sizing "border-box"
                               :padding "0.5em"
                               :font-size "0.95em"
                               :border "1px solid #ccc"
                               :border-radius "0.3em"}}]])

(defn- altcha-captcha
  "The self-hosted ALTCHA proof-of-work widget. It fetches its challenge from our
  endpoint, solves it in a worker, and on success stores the payload in the form so
  the register POST can carry it (the server re-verifies).

  `lang` (\"fr\"/\"en\") picks the widget's UI language; \"fr\" resolves to the
  registered \"fr-fr\" strings. NOTE: ALTCHA only runs in a *secure context* — HTTPS,
  or http://localhost / http://127.0.0.1. Over plain HTTP on any other host (a LAN IP,
  0.0.0.0, a *.local name) `crypto.subtle` is unavailable and the widget shows the
  generic \"Verification failed\" — use http://localhost in dev."
  [_]
  (let [attach (fn [el]
                 (when el
                   (.addEventListener el
                                      "statechange"
                                      (fn [e]
                                        (let [d (.-detail e)]
                                          (rf/dispatch [::set-form-property
                                                        :altcha
                                                        (when (= (.-state d) "verified")
                                                          (.-payload d))]))))))]
    (fn [lang] [:altcha-widget {:ref attach
                                ;; v3 attribute is `challenge` (a URL fetches; inline JSON starts with `{`);
                                ;; `challengeurl` was the old v0.x name and is silently ignored → empty fetch.
                                :challenge "/agora/api/auth/altcha-challenge"
                                :language (name lang)
                                :style {:display "block"
                                        :margin-bottom "0.8em"}}])))

(defn auth-modal
  "Login / registration overlay, shown when a form is open."
  []
  (when-let [{:keys [mode email password error submitting? altcha]} @(rf/subscribe [::form])]
    (let [register? (= mode :register)
          lang @(rf/subscribe [::i18n/lang])
          ;; on register, the button stays disabled until the ALTCHA payload is present
          blocked? (or (boolean submitting?) (and register? (str/blank? altcha)))]
      [:div {:on-click #(rf/dispatch [::close-form])
             :style {:position "fixed"
                     :inset 0
                     :z-index 100
                     :background "rgba(0,0,0,0.45)"
                     :display "flex"
                     :align-items "flex-start"
                     :justify-content "center"
                     :padding-top "10vh"}}
       [ui/on-escape #(rf/dispatch [::close-form])]
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
         #(rf/dispatch [::set-form-property :email %])]
        [field
         (i18n/t lang :auth/password)
         "password"
         password
         #(rf/dispatch [::set-form-property :password %])]
        (when register? [altcha-captcha lang])
        (when error
          [:div {:style {:color "#c92a2a"
                         :font-size "0.85em"
                         :margin-bottom "0.6em"}}
           error])
        [:button {:on-click #(when-not blocked? (rf/dispatch [::submit]))
                  :disabled blocked?
                  :style {:width "100%"
                          :padding "0.55em"
                          :border "none"
                          :background "#b9770e"
                          :color "#fff"
                          :border-radius "0.3em"
                          :font-size "1em"
                          :opacity (if blocked? 0.7 1)
                          :cursor (if blocked? "default" "pointer")}}
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
