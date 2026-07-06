(ns landing.admin
  "Admin SPA — at-a-glance status of every reachability ping, HTML page, and
  CSS file the site cares about. Rendered into the static
  `resources/public/all-kind-of-checks.html` shell."
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

(goog-define ENV "dev")

;; ********************************************************************************
;; Status helpers — used by every row
;; ********************************************************************************

(defn- status-info
  "Colour / icon / one-word label for a re-frame fetch status."
  [status]
  (case status
    :success {:cls "w3-green"
              :icon "✓"
              :label "OK"}
    :failure {:cls "w3-red"
              :icon "✕"
              :label "Fail"}
    :in-progress {:cls "w3-amber"
                  :icon "…"
                  :label "Pending"}
    {:cls "w3-light-grey"
     :icon "?"
     :label "?"}))

(defn- count-by-status
  "Returns {:ok n :fail n :pending n :other n} for a coll of status keywords."
  [statuses]
  (reduce (fn [acc s]
            (let [bucket (case s
                           :success :ok
                           :failure :fail
                           :in-progress :pending
                           :other)]
              (update acc bucket inc)))
          {:ok 0
           :fail 0
           :pending 0
           :other 0}
          statuses))

(defn- validator-result
  "Parsed W3C validator report for a `w3c-validate` fetch response, or nil when
  this isn't a validator response (e.g. a reachability failure) or the body
  can't be parsed. fetch-fx delivers the endpoint's JSON body as a *text
  string* under `:body` (no `:response-content-types` is configured), and the
  validator's own JSON lives inside that body's `:curl-data` field."
  [res]
  (when-let [raw (:body res)]
    (try (let [curl (:curl-data (js->clj (js/JSON.parse raw) :keywordize-keys true))]
           (when curl (js->clj (js/JSON.parse curl) :keywordize-keys true)))
         (catch :default _ nil))))

(defn- validation-errors
  "Number of blocking errors reported by the W3C validator. Returns nil when
  `res` isn't a validator response. Covers both the Nu HTML validator
  (`messages` with type \"error\") and the Jigsaw CSS validator
  (`cssvalidation.errors`)."
  [res]
  (when-let [data (validator-result res)]
    (let [html-errors (->> (:messages data)
                           (filter #(= "error" (:type %)))
                           count)
          css-errors (count (get-in data [:cssvalidation :errors]))]
      (+ html-errors css-errors))))

(defn- summarize
  "Short, one-line summary of a fetch response for the collapsed row."
  [status res]
  (case status
    :in-progress "pending…"
    :success (or (some-> res
                         :status
                         str)
                 "ok")
    :failure (let [n (validation-errors res)]
               (cond
                 (number? n)
                 (if (pos? n) (str n " validation error" (when (> n 1) "s")) "validation failed")
                 (:status res) (str "HTTP " (:status res))
                 (:problem res) (name (:problem res))
                 (:error res) (str (:error res))
                 :else "error"))
    "—"))

;; ********************************************************************************
;; Filter + expand state
;; ********************************************************************************

(def default-db
  {::filter-mode :failing
   ::expanded #{}})

(reg-event-db ::initialize-db (fn [_ _] default-db))

(reg-sub ::filter-mode (fn [db _] (::filter-mode db)))
(reg-event-db ::set-filter-mode (fn [db [_ mode]] (assoc db ::filter-mode mode)))

(reg-sub ::expanded? (fn [db [_ row-id]] (contains? (::expanded db) row-id)))

(reg-event-db
 ::toggle-expanded
 (fn [db [_ row-id]]
   (update db ::expanded (fn [s] (if (contains? s row-id) (disj s row-id) (conj s row-id))))))

(defn- visible-under-filter?
  [mode status]
  (case mode
    :all true
    :failing (= status :failure)
    :pending (= status :in-progress)
    true))

;; ********************************************************************************
;; Reachability checks (server-side ping)
;; ********************************************************************************

(defn- to-absolute-url
  [url]
  (-> (try (.-href (js/URL. url js/window.location.href)) (catch :default _ url))
      js/encodeURIComponent))

(defn- current-origin
  "Origin of the page being viewed (e.g. the la/prod deployment, or localhost),
  so checks validate the deployment you are actually looking at rather than a
  hardcoded host."
  []
  js/window.location.origin)

(def ^:private cache-bust-manifest
  "Original-path -> fingerprinted-path map injected into the admin shell's
  `<script id=\"cache-bust-manifest\">` by `cache-bust/fingerprint-jar!` at
  deploy time. Empty `{}` in dev (no fingerprinting)."
  (or (some-> (.getElementById js/document "cache-bust-manifest")
              .-textContent
              js/JSON.parse
              js->clj)
      {}))

(defn- fingerprint-path
  "Resolve `path` (relative to the site root, e.g. \"css/custom.css\") to its
  fingerprinted name on la/prod, else return it unchanged."
  [path]
  (get cache-bust-manifest path path))

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

(reg-sub ::all-ping-statuses
         (fn [db _] (for [[_ by-id] (:check-url-status db) [_ {:keys [status]}] by-id] status)))

;; ********************************************************************************
;; W3C validation (CSS + HTML)
;; ********************************************************************************

(defn- validation-event
  [kw]
  (fn [{:keys [db]} [_ id url]]
    {:fetch {:method :get
             :url "w3c-validate"
             :headers {"Accept" "application/json"}
             :mode :cors
             :params {kw (name id)
                      :domain (current-origin)}
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
              (fn [{:keys [db]} [_ id status res]]
                ;; The endpoint returns HTTP 200 even when a page fails
                ;; validation, so a fetch :success only means the validator
                ;; was reached — inspect its body to decide pass/fail.
                (let [status (if (and (= status :success) (pos? (or (validation-errors res) 0)))
                               :failure
                               status)]
                  {:db (update-in db [::validation-response id] assoc :status status :res res)})))

(reg-sub ::all-validation-statuses (fn [db _] (map (comp :status val) (::validation-response db))))

;; Statuses for a specific set of validation ids (one section's manifest), so a
;; section header counts exactly the rows rendered below it. `::all-validation-
;; statuses` mixes HTML + CSS and is only correct for the grand total.
(reg-sub ::validation-statuses-for
         (fn [db [_ ids]] (map #(get-in db [::validation-response % :status]) ids)))

(doseq [[css-id css-url] w3c-validate-css] (dispatch [::do-css-validation css-id css-url]))
(doseq [[html-id html-url] w3c-validate-htmls] (dispatch [::do-html-validation html-id html-url]))

;; ********************************************************************************
;; Generic row + section components
;; ********************************************************************************

(defn- pre-block
  [v]
  [:pre {:style {:margin 0
                 :white-space "pre-wrap"
                 :word-break "break-word"
                 :font-size "0.85em"
                 :max-height "20em"
                 :overflow "auto"
                 :background "#f5f5f5"
                 :padding "0.5em"
                 :border-left "3px solid #bbb"}}
   (with-out-str (pprint v))])

(defn- status-dot
  [status]
  (let [{:keys [cls icon]} (status-info status)]
    [:span.w3-tag {:style {:width "1.6em"
                           :text-align "center"
                           :padding "0 0.3em"
                           :line-height "1.4em"
                           :font-family "monospace"}
                   :class cls}
     icon]))

(defn- row
  "One status row — clicking the row toggles an inline detail panel.
  `detail-rows` is a map of label → value rendered as a small definition list."
  [{:keys [row-id status name target summary detail-rows external-url]}]
  (let [expanded? @(subscribe [::expanded? row-id])
        chevron (if expanded? "▾" "▸")]
    [:div.w3-border-bottom.w3-hover-light-grey {:key row-id
                                                :style {:padding "0.3em 0.4em"
                                                        :cursor "pointer"}
                                                :on-click #(dispatch [::toggle-expanded row-id])}
     [:div.w3-flex {:style {:align-items "center"
                            :gap "0.6em"}}
      [status-dot status]
      [:span {:style {:flex "0 0 14em"
                      :font-weight 600
                      :overflow "hidden"
                      :text-overflow "ellipsis"
                      :white-space "nowrap"}}
       name]
      [:span {:style {:flex "1 1 auto"
                      :color "#666"
                      :font-family "monospace"
                      :font-size "0.85em"
                      :overflow "hidden"
                      :text-overflow "ellipsis"
                      :white-space "nowrap"}}
       target]
      [:span {:style {:flex "0 0 8em"
                      :text-align "right"
                      :font-size "0.85em"
                      :color "#444"}}
       summary]
      [:span {:style {:flex "0 0 1em"
                      :color "#888"}}
       chevron]]
     (when expanded?
       [:div {:style {:padding "0.6em 0.6em 0.6em 2.5em"
                      :background "#fafafa"
                      :font-size "0.9em"}
              :on-click #(.stopPropagation %)}
        [:table {:style {:border-collapse "collapse"
                         :margin-bottom "0.4em"}}
         [:tbody
          (for [[label v] detail-rows
                :when (some? v)]
            ^{:key label}
            [:tr
             [:td {:style {:padding "0.1em 0.8em 0.1em 0"
                           :vertical-align "top"
                           :color "#666"
                           :font-weight 600}}
              label]
             [:td {:style {:vertical-align "top"
                           :font-family "monospace"
                           :font-size "0.9em"}}
              (if (or (map? v) (sequential? v)) [pre-block v] (str v))]])]]
        (when external-url
          [:a {:href external-url
               :target "_blank"
               :rel "noopener"}
           "↗ open externally"])])]))

(defn- section
  "Heading + short description + a stack of rows. `count-info` is shown next
  to the title."
  [{:keys [title description rows count-info]}]
  [:section {:style {:margin-bottom "1.5em"}}
   [:div.w3-flex {:style {:align-items "baseline"
                          :gap "0.8em"
                          :padding "0.4em 0.6em"
                          :background "#fafafa"
                          :border-bottom "2px solid #ddd"}}
    [:h2 {:style {:font-size "1em"
                  :margin "0"
                  :flex "0 0 auto"}}
     title]
    [:span {:style {:flex "1 1 auto"
                    :font-size "0.85em"
                    :color "#666"}}
     description]
    (when count-info
      [:span {:style {:font-size "0.85em"
                      :font-family "monospace"}}
       count-info])]
   (if (seq rows)
     (into [:div] rows)
     [:div {:style {:padding "0.6em"
                    :font-style "italic"
                    :color "#888"}}
      "Nothing to show under the current filter."])])

(defn- counts-chip
  [{:keys [ok fail pending]}]
  [:span {:style {:font-family "monospace"
                  :font-size "0.85em"}}
   [:span {:style {:color "#178c4b"}}
    "✓ "
    ok]
   "  " [:span {:style {:color "#c0392b"}}
         "✕ "
         fail]
   "  " [:span {:style {:color "#b9770e"}}
         "… "
         pending]])

;; ********************************************************************************
;; Sections — quick links, reachability, html validation, css validation
;; ********************************************************************************

(defn- quick-links-section
  []
  [section
   {:title "Quick links"
    :description "Local dashboards, deployed environments, and feature pages."
    :rows
    [(let [items
           (concat
            [["Main page" "/"]
             ["Swagger" "/api/api-docs/"]
             ["Trigger 404" "/non-existing-page"]
             ["Github project" "https://github.com/hephaistox/landing"]
             ["Project board" "https://github.com/orgs/hephaistox/projects/1/views/3"]
             ["Local acceptance"
              "https://app-e8b6e958-4eeb-4d93-b14e-a13a256c0e37.cleverapps.io/all-kind-of-checks"]
             ["Production" "https://www.hephaistox.fr/all-kind-of-checks"]]
            (when (= "dev" ENV)
              [["Shadow-cljs" "http://localhost:9551/dashboard"]
               ["Browser test" "http://localhost:9651/"]]))]
       [:div {:style {:padding "0.6em"
                      :display "grid"
                      :grid-template-columns "repeat(auto-fill, minmax(14em, 1fr))"
                      :gap "0.3em 1em"}}
        (for [[label url] items]
          ^{:key url}
          [:a {:href url
               :target "_blank"
               :rel "noopener"
               :class "w3-hover-light-grey"
               :style {:font-size "0.9em"}}
           label])])]}])

(defn- ping-rows
  [filter-mode]
  (for [[origin grouped] (sort-by first (group-by :origin links))
        :let [origin-rows
              (for [{:keys [link-id url]} grouped
                    :let [row-id (str "ping/" origin "/" (name link-id))
                          {:keys [status res]} @(subscribe [::ping-response origin link-id])]
                    :when (visible-under-filter? filter-mode status)]
                ^{:key row-id}
                [row {:row-id row-id
                      :status status
                      :name (name link-id)
                      :target url
                      :summary (summarize status res)
                      :external-url url
                      :detail-rows [["origin" origin]
                                    ["link-id" (name link-id)]
                                    ["target" url]
                                    ["domain (probe)" (js/decodeURIComponent (to-absolute-url "/"))]
                                    ["status"
                                     (some-> status
                                             name)]
                                    ["result" res]]}])]
        :when (seq origin-rows)]
    ^{:key origin}
    [:div {:style {:margin "0.4em 0"}}
     [:div {:style {:padding "0.3em 0.6em"
                    :background "#f0f0f0"
                    :font-family "monospace"
                    :font-size "0.8em"
                    :color "#444"}}
      origin]
     (into [:div] origin-rows)]))

(defn- reachability-section
  []
  (let [filter-mode @(subscribe [::filter-mode])
        statuses @(subscribe [::all-ping-statuses])]
    [section {:title "Reachability"
              :description (str
                            "Server-side GET against every external URL the site links to "
                            "(via the /check-url backend endpoint). Grouped by source namespace.")
              :count-info [counts-chip (count-by-status statuses)]
              :rows (ping-rows filter-mode)}]))

(defn- validation-rows
  [kind url-prefix validator-url id->path]
  (let [filter-mode @(subscribe [::filter-mode])]
    (for [[id path] (sort-by first id->path)
          :let [row-id (str kind "/" (name id))
                {:keys [status res url]} @(subscribe [::validation-response id])
                ;; Resolve the asset through the cache-bust manifest injected
                ;; into the shell (e.g. css/custom.css → css/custom.34f0a84f.css)
                ;; so the external validator link points at the file that
                ;; actually exists on the deployment — no round-trip needed.
                ext (str validator-url url-prefix (fingerprint-path url))]
          :when (visible-under-filter? filter-mode status)]
      ^{:key row-id}
      [row {:row-id row-id
            :status status
            :name (name id)
            :target path
            :summary (summarize status res)
            :external-url ext
            :detail-rows [["id" (name id)]
                          ["path" path]
                          ["status"
                           (some-> status
                                   name)]
                          ["validator" ext]
                          ["result" res]]}])))

(defn- html-validation-section
  []
  (let [statuses @(subscribe [::validation-statuses-for (keys w3c-validate-htmls)])]
    [section {:title "HTML validation"
              :description
              "Each page sent to validator.w3.org/nu; rows expand to show the W3C report."
              :count-info [counts-chip (count-by-status (filter some? statuses))]
              :rows (validation-rows "html" (str (current-origin) "/")
                                     "https://validator.w3.org/nu/?doc=" w3c-validate-htmls)}]))

(defn- css-validation-section
  []
  (let [statuses @(subscribe [::validation-statuses-for (keys w3c-validate-css)])]
    [section {:title "CSS validation"
              :description "Each stylesheet sent to jigsaw.w3.org/css-validator."
              :count-info [counts-chip (count-by-status (filter some? statuses))]
              :rows (validation-rows "css" (str (current-origin) "/")
                                     "https://jigsaw.w3.org/css-validator/validator?uri="
                                     w3c-validate-css)}]))

;; ********************************************************************************
;; Top bar
;; ********************************************************************************

(defn- filter-chip
  [mode label]
  (let [current @(subscribe [::filter-mode])
        active? (= current mode)]
    [:button.w3-button {:on-click #(dispatch [::set-filter-mode mode])
                        :style (merge {:padding "0.2em 0.7em"
                                       :margin-right "0.2em"
                                       :font-size "0.85em"
                                       :border "1px solid #bbb"
                                       :border-radius "0.3em"
                                       :background "#fff"}
                                      (when active?
                                        {:background "#2c3e50"
                                         :color "#fff"
                                         :border-color "#2c3e50"
                                         :font-weight 600}))}
     label]))

(defn- top-bar
  []
  (let [ping (count-by-status @(subscribe [::all-ping-statuses]))
        valid (count-by-status (filter some? @(subscribe [::all-validation-statuses])))
        total {:ok (+ (:ok ping) (:ok valid))
               :fail (+ (:fail ping) (:fail valid))
               :pending (+ (:pending ping) (:pending valid))}]
    [:div.w3-card {:style {:position "sticky"
                           :top "0"
                           :z-index 10
                           :background "#fff"
                           :padding "0.5em 0.8em"
                           :margin-bottom "0.8em"
                           :border-bottom "2px solid #2c3e50"}}
     [:div.w3-flex {:style {:align-items "center"
                            :gap "1em"
                            :flex-wrap "wrap"}}
      [:strong {:style {:font-size "1.1em"}}
       "Hephaistox · checks"]
      [:span.w3-tag {:style {:background (if (= "dev" ENV) "#8e44ad" "#16a085")
                             :color "#fff"
                             :padding "0.1em 0.5em"
                             :font-size "0.75em"
                             :border-radius "0.2em"}}
       ENV]
      [:span {:style {:flex "1 1 auto"}}]
      [counts-chip total]
      [:span {:style {:width "1em"}}]
      [filter-chip :all "All"]
      [filter-chip :failing "Failing"]
      [filter-chip :pending "Pending"]]]))

;; ********************************************************************************
;; Root
;; ********************************************************************************

(defn admin-rf-body
  []
  [:div {:style {:max-width "70em"
                 :margin "0 auto"
                 :padding "0 0.6em 2em"
                 :font-family "system-ui, sans-serif"
                 :font-size "0.95em"}}
   [top-bar]
   [quick-links-section]
   [reachability-section]
   [html-validation-section]
   [css-validation-section]])

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
  (mount-root))
