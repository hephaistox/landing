(ns landing.agora.frontend.ki-view
  "The KI page: the focused Knowledge Item as an editable card, its input KIs as
  mini cards above (each removable, with an add-input control by the arrows) and
  its successor KIs as mini cards below, joined by directed connectors.

  Editing happens in place on the card (a pencil toggles the fields). Input links
  are managed where the inputs are shown: a drop control on each input link, and
  an add/search/create control next to the top connector. Edit and link
  operations POST to the API and fold the result back into the view via
  :agora/edited / :agora/ki-updated (registered in core).

  Not auth-gated yet — OAuth arrives in #38."
  (:require
   [clojure.string             :as str]
   [landing.agora.frontend.fmt :as fmt]
   [re-frame.core              :as rf]
   [reagent.core               :as r]
   [superstructor.re-frame.fetch-fx]))

;; ===========================================================================
;; Types
;; ===========================================================================

(def ^:private type-badge
  "Display label + accent colour per KI type."
  {"derived" {:label "Derived"
              :bg "#2c5aa0"}
   "verifiable-claim" {:label "Verifiable claim"
                       :bg "#0b7285"}
   "postulate" {:label "Postulate"
                :bg "#6741d9"}
   "stance" {:label "Stance"
             :bg "#b9770e"}
   "belief" {:label "Belief"
             :bg "#2b8a3e"}
   "credo" {:label "Credo"
            :bg "#c92a2a"}})

(def ki-types
  "The KI types, in display order."
  ["derived" "verifiable-claim" "postulate" "stance" "belief" "credo"])

(defn type-badge-view
  [ki-type]
  (let [{:keys [label bg]} (get type-badge
                                ki-type
                                {:label (or ki-type "?")
                                 :bg "#666"})]
    [:span {:style {:display "inline-block"
                    :background bg
                    :color "#fff"
                    :font-size "0.7em"
                    :font-weight 700
                    :letter-spacing "0.05em"
                    :text-transform "uppercase"
                    :padding "0.2em 0.6em"
                    :border-radius "0.25em"}}
     label]))

(defn type-selector
  "All KI types as clickable badges; the selected one is highlighted, the others
  dimmed. Calls `on-select` with the chosen type string."
  [selected on-select]
  (into [:div {:style {:display "flex"
                       :flex-wrap "wrap"
                       :gap "0.4em"}}]
        (for [t ki-types
              :let [current? (= t selected)]]
          ^{:key t}
          [:button {:on-click #(on-select t)
                    :title t
                    :style {:border "none"
                            :background "transparent"
                            :padding "0.1em"
                            :cursor "pointer"
                            :border-radius "0.3em"
                            :opacity (if current? 1 0.35)
                            :box-shadow (if current? "0 0 0 2px #333" "none")}}
           [type-badge-view t]])))

;; ===========================================================================
;; State + operations (edit + links)
;; ===========================================================================

(rf/reg-sub ::edit (fn [db _] (::edit db)))
(rf/reg-sub ::links (fn [db _] (::links db)))

(defn close-panels
  "Collapse the edit form and any open add-input panel. Used by core on route
  changes so nothing lingers from a previous KI."
  [db]
  (-> db
      (update ::edit assoc :open? false)
      (assoc ::links
             {:adding? false
              :creating? false
              :q ""
              :results []})))

(defn- json-req
  [method url body on-success]
  {:method method
   :url url
   :headers {"Content-Type" "application/json"
             "Accept" "application/json"}
   :body (js/JSON.stringify (clj->js body))
   :response-content-types {#"application/json" :json}
   :on-success on-success
   :on-failure [::op-failed]})

(rf/reg-event-db ::op-failed
                 (fn [db [_ resp]]
                   (js/console.error "[agora] operation failed:" (clj->js resp))
                   (update db ::edit assoc :saving? false :error resp)))

;; ---- Edit (new minor version) ----

(rf/reg-event-db ::edit-open
                 (fn [db [_ ki]]
                   (assoc db
                          ::edit
                          {:open? true
                           :type (:type ki)
                           :output-statement (:output-statement ki)
                           :saving? false
                           :error nil})))

(rf/reg-event-db ::edit-close (fn [db _] (update db ::edit assoc :open? false)))
(rf/reg-event-db ::edit-set (fn [db [_ k v]] (update db ::edit assoc k v)))

(rf/reg-event-fx ::edit-save
                 (fn [{:keys [db]} [_ ki-id]]
                   (let [{:keys [type output-statement]} (::edit db)]
                     {:db (update db ::edit assoc :saving? true :error nil)
                      :fetch (json-req :post
                                       (str "/api/ki/" ki-id "/edit")
                                       {:type type
                                        :output-statement output-statement}
                                       [::edit-save-ok])})))

(rf/reg-event-fx ::edit-save-ok
                 (fn [{:keys [db]} [_ resp]]
                   (let [ki (:body resp)]
                     (if (:id ki)
                       {:dispatch [:agora/edited ki]}
                       {:db (update db ::edit assoc :saving? false :error resp)}))))

;; ---- Input links (add / drop / search / create) ----

(rf/reg-event-db ::links-open-add
                 (fn [db _]
                   (assoc db
                          ::links
                          {:adding? true
                           :creating? false
                           :q ""
                           :results []})))

(rf/reg-event-db ::links-reset
                 (fn [db _]
                   (assoc db
                          ::links
                          {:adding? false
                           :creating? false
                           :q ""
                           :results []})))

(rf/reg-event-db ::links-open-create
                 (fn [db _]
                   (update db
                           ::links
                           merge
                           {:creating? true
                            :new-name ""
                            :new-type "derived"
                            :new-statement ""})))

(rf/reg-event-db ::links-set (fn [db [_ k v]] (update db ::links assoc k v)))

(rf/reg-event-fx ::links-search
                 (fn [{:keys [db]} [_ q]]
                   (cond-> {:db (assoc-in db [::links :q] q)}
                     (not (str/blank? q)) (assoc :fetch
                                                 {:method :get
                                                  :url (str "/api/ki?q=" (js/encodeURIComponent q))
                                                  :headers {"Accept" "application/json"}
                                                  :response-content-types {#"application/json"
                                                                           :json}
                                                  :on-success [::links-search-ok]
                                                  :on-failure [::op-failed]}))))

(rf/reg-event-db ::links-search-ok (fn [db [_ resp]] (assoc-in db [::links :results] (:body resp))))

(rf/reg-event-fx ::add-input
                 (fn [_ [_ ki-id ref]]
                   {:fetch
                    (json-req :post (str "/api/ki/" ki-id "/inputs") ref [::input-changed])}))

(rf/reg-event-fx ::drop-input
                 (fn [_ [_ ki-id ref]]
                   {:fetch
                    (json-req :delete (str "/api/ki/" ki-id "/inputs") ref [::input-changed])}))

(rf/reg-event-fx ::create-and-add
                 (fn [{:keys [db]} [_ ki-id]]
                   (let [{:keys [new-name new-type new-statement]} (::links db)]
                     {:fetch (json-req :post
                                       "/api/ki"
                                       {:name new-name
                                        :type new-type
                                        :output-statement new-statement}
                                       [::created ki-id])})))

(rf/reg-event-fx ::created
                 (fn [_ [_ ki-id resp]]
                   {:dispatch [::add-input ki-id (select-keys (:body resp) [:name :major])]}))

(rf/reg-event-fx ::input-changed
                 (fn [{:keys [db]} [_ resp]]
                   {:db (assoc db
                               ::links
                               {:adding? false
                                :creating? false
                                :q ""
                                :results []})
                    :dispatch [:agora/ki-updated (:body resp)]}))

;; ===========================================================================
;; Components
;; ===========================================================================

(def ^:private version-tag-style
  {:color "#aaa"
   :font-size "0.72em"
   :font-family "monospace"})

(defn version-picker
  "Current version; clicking reveals an in-order strip of every version."
  [{:keys [major minor versions]}]
  (r/with-let
   [open? (r/atom false)]
   [:span {:style {:display "inline-flex"
                   :align-items "center"
                   :gap "0.4em"
                   :font-family "monospace"
                   :font-size "0.8em"}}
    [:button {:on-click #(swap! open? not)
              :title "Show all versions"
              :style {:font-family "inherit"
                      :font-size "inherit"
                      :color "#888"
                      :background "transparent"
                      :border "1px solid #ddd"
                      :border-radius "0.3em"
                      :padding "0.1em 0.5em"
                      :cursor "pointer"}}
     (str "v" major "." minor " " (if @open? "▴" "▾"))]
    (when @open?
      (into [:span {:style {:display "inline-flex"
                            :gap "0.3em"
                            :max-width "22em"
                            :overflow-x "auto"
                            :padding "0.1em"}}]
            (for [v (sort-by :minor versions)
                  :let [current? (= (:minor v) minor)]]
              ^{:key (:id v)}
              [:a {:href (str "/lab/ki/" (:id v))
                   :on-click #(reset! open? false)
                   :style {:flex "0 0 auto"
                           :text-decoration "none"
                           :padding "0.1em 0.5em"
                           :border-radius "0.3em"
                           :border (str "1px solid " (if current? "#b9770e" "#ddd"))
                           :background (if current? "#b9770e" "#fff")
                           :color (if current? "#fff" "#b9770e")}}
               (str "v" major "." (:minor v))])))]))

(defn- mini-card
  "A compact neighbour card linking to its own page. When `on-drop` is given, a ✕
  removes the link (used for input links)."
  [{c-id :id
    c-name :name
    c-type :type
    :keys [major minor]}
   on-drop]
  [:div {:style {:position "relative"
                 :width "16em"
                 :max-width "100%"}}
   [:a {:href (str "/lab/ki/" c-id)
        :style {:display "block"
                :box-sizing "border-box"
                :text-decoration "none"
                :color "inherit"
                :padding "0.55em 0.7em"
                :border "1px solid #ddd"
                :border-radius "0.4em"
                :background "#fff"}}
    [:div {:style {:display "flex"
                   :align-items "center"
                   :gap "0.5em"
                   :margin-bottom "0.3em"}}
     [type-badge-view c-type]
     [:span {:style version-tag-style}
      (str "v" major "." minor)]]
    [:div {:style {:font-weight 600
                   :font-size "0.9em"}}
     c-name]]
   (when on-drop
     [:button {:on-click on-drop
               :title "Remove this input link"
               :style {:position "absolute"
                       :top "0.2em"
                       :right "0.3em"
                       :border "none"
                       :background "transparent"
                       :color "#c92a2a"
                       :cursor "pointer"
                       :font-size "0.85em"
                       :line-height 1}}
      "✕"])])

(defn- connector
  "A short vertical directed link (arrow down = direction of implication)."
  []
  [:div {:style {:display "flex"
                 :flex-direction "column"
                 :align-items "center"
                 :margin "0.15em 0"}}
   [:div {:style {:width "2px"
                  :height "1.2em"
                  :background "#d9b38c"}}]
   [:div {:style {:color "#b9770e"
                  :font-size "0.95em"
                  :line-height "1"}}
    "▼"]])

(defn- create-input-form
  [ki-id {:keys [new-name new-type new-statement]}]
  (let [field {:width "100%"
               :box-sizing "border-box"
               :padding "0.4em"
               :font-family "inherit"
               :font-size "0.9em"
               :border "1px solid #ccc"
               :border-radius "0.3em"
               :margin-bottom "0.5em"}]
    [:div {:style {:margin-top "0.5em"
                   :padding "0.7em"
                   :border "1px solid #ddd"
                   :border-radius "0.4em"
                   :background "#fff"
                   :text-align "left"}}
     [:div {:style {:font-size "0.75em"
                    :color "#888"
                    :margin-bottom "0.4em"}}
      "New input KI"]
     [:input {:type "text"
              :placeholder "name"
              :value new-name
              :on-change #(rf/dispatch [::links-set :new-name (.. % -target -value)])
              :style field}]
     [:div {:style {:margin-bottom "0.5em"}}
      [type-selector new-type #(rf/dispatch [::links-set :new-type %])]]
     [:textarea {:placeholder "output statement"
                 :rows 2
                 :value new-statement
                 :on-change #(rf/dispatch [::links-set :new-statement (.. % -target -value)])
                 :style field}]
     [:button {:on-click #(rf/dispatch [::create-and-add ki-id])
               :disabled (or (str/blank? new-name) (str/blank? new-statement))
               :style {:padding "0.35em 0.8em"
                       :border "none"
                       :background "#b9770e"
                       :color "#fff"
                       :border-radius "0.3em"
                       :cursor "pointer"}}
      "Create & add"]]))

(defn- add-input-control
  "The add-input affordance, sitting by the top connector. Search results exclude
  the KI itself (no self-loop) and any KI already an input."
  [ki]
  (let [ui @(rf/subscribe [::links])
        ki-id (:id ki)
        excluded (into #{[(:name ki) (:major ki)]} (map (juxt :name :major) (:inputs ki)))
        results (remove #(excluded [(:name %) (:major %)]) (:results ui))]
    (if-not (:adding? ui)
      [:button {:on-click #(rf/dispatch [::links-open-add])
                :style {:font-size "0.8em"
                        :background "transparent"
                        :border "1px dashed #b9770e"
                        :color "#b9770e"
                        :border-radius "0.3em"
                        :padding "0.25em 0.7em"
                        :cursor "pointer"}}
       "+"]
      [:div {:style {:width "22em"
                     :max-width "100%"
                     :text-align "center"}}
       [:div {:style {:display "flex"
                      :justify-content "flex-end"}}
        [:button {:on-click #(rf/dispatch [::links-reset])
                  :title "Close"
                  :style {:border "none"
                          :background "transparent"
                          :color "#999"
                          :cursor "pointer"
                          :font-size "1.1em"
                          :line-height 1
                          :padding "0 0.1em"}}
         "×"]]
       [:input {:type "text"
                :placeholder "Search a KI by name…"
                :value (:q ui)
                :on-change #(rf/dispatch [::links-search (.. % -target -value)])
                :style {:width "100%"
                        :box-sizing "border-box"
                        :padding "0.45em"
                        :font-family "inherit"
                        :font-size "0.9em"
                        :border "1px solid #ccc"
                        :border-radius "0.3em"}}]
       (when (seq results)
         (into [:div {:style {:display "flex"
                              :flex-wrap "wrap"
                              :gap "0.3em"
                              :justify-content "center"
                              :margin-top "0.5em"}}]
               (for [r results]
                 ^{:key (:id r)}
                 [:button {:on-click #(rf/dispatch
                                       [::add-input ki-id (select-keys r [:name :major])])
                           :title "Add as input"
                           :style {:cursor "pointer"
                                   :border "1px solid #e3c48f"
                                   :background "#fff"
                                   :border-radius "0.3em"
                                   :padding "0.25em 0.5em"}}
                  [:span {:style {:display "inline-flex"
                                  :align-items "center"
                                  :gap "0.4em"
                                  :font-size "0.85em"}}
                   [type-badge-view (:type r)]
                   [:span (:name r)]
                   [:span {:style version-tag-style}
                    (str "v" (:major r) "." (:minor r))]]])))
       (when (and (not (str/blank? (:q ui))) (empty? results))
         [:div {:style {:color "#aaa"
                        :font-size "0.85em"
                        :margin "0.4em 0"}}
          "No matches."])
       (if (:creating? ui)
         [create-input-form ki-id ui]
         [:div {:style {:margin-top "0.4em"}}
          [:button {:on-click #(rf/dispatch [::links-open-create])
                    :style {:font-size "0.8em"
                            :background "transparent"
                            :border "1px dashed #b9770e"
                            :color "#b9770e"
                            :border-radius "0.3em"
                            :padding "0.25em 0.7em"
                            :cursor "pointer"}}
           "+ create a new KI"]])])))

(def ^:private card-style
  {:position "relative"
   :width "40em"
   :max-width "100%"
   :box-sizing "border-box"
   :padding "1.25em 1.5em"
   :border "1px solid #ccc"
   :border-radius "0.5em"
   :background "#fff"
   :box-shadow "0 1px 3px rgba(0,0,0,0.06)"
   :font-family "system-ui, sans-serif"})

(defn- edit-card
  "The card in edit mode: type selector + editable statement, in place."
  [{ki-name :name
    :keys [id major minor published-at]}
   {:keys [type output-statement saving? error]}]
  [:article {:style card-style}
   [:div {:style {:display "flex"
                  :align-items "center"
                  :gap "0.75em"
                  :margin-bottom "0.6em"}}
    [type-selector type #(rf/dispatch [::edit-set :type %])]
    [:span {:style {:color "#888"
                    :font-size "0.8em"
                    :font-family "monospace"}}
     (str "v" major "." minor " → next")]]
   [:h1 {:style {:font-size "1.3em"
                 :margin "0.2em 0 0.1em"}}
    ki-name]
   [:div {:style {:color "#888"
                  :font-size "0.8em"
                  :margin-bottom "0.7em"}}
    (or (fmt/utc published-at) "—")]
   [:textarea {:value output-statement
               :rows 4
               :on-change #(rf/dispatch [::edit-set :output-statement (.. % -target -value)])
               :style {:width "100%"
                       :box-sizing "border-box"
                       :padding "0.5em"
                       :font-family "inherit"
                       :font-size "1.02em"
                       :line-height "1.5"
                       :border "1px solid #ccc"
                       :border-radius "0.3em"}}]
   (when error
     [:div {:style {:color "#c92a2a"
                    :font-size "0.85em"
                    :margin-top "0.5em"}}
      "Save failed — see console."])
   [:div {:style {:display "flex"
                  :gap "0.5em"
                  :margin-top "0.7em"}}
    [:button {:on-click #(rf/dispatch [::edit-save id])
              :disabled (boolean saving?)
              :style {:padding "0.4em 0.9em"
                      :border "none"
                      :background "#b9770e"
                      :color "#fff"
                      :border-radius "0.3em"
                      :cursor (if saving? "default" "pointer")}}
     (if saving? "Saving…" "Save new version")]
    [:button {:on-click #(rf/dispatch [::edit-close])
              :style {:padding "0.4em 0.9em"
                      :border "1px solid #ccc"
                      :background "#fff"
                      :border-radius "0.3em"
                      :cursor "pointer"}}
     "Cancel"]]])

(defn- static-card
  "The card in read mode, with a pencil that switches to in-place editing."
  [{ki-name :name
    ki-type :type
    :keys [major minor published-at output-statement versions]
    :as ki}]
  [:article {:style card-style}
   [:button {:on-click #(rf/dispatch [::edit-open ki])
             :title "Edit — create a new version"
             :style {:position "absolute"
                     :top "0.7em"
                     :right "0.8em"
                     :border "1px solid #ddd"
                     :background "#fff"
                     :color "#b9770e"
                     :border-radius "0.3em"
                     :width "2em"
                     :height "2em"
                     :cursor "pointer"
                     :font-size "0.95em"
                     :line-height 1}}
    "✎"]
   [:div {:style {:display "flex"
                  :align-items "center"
                  :gap "0.75em"
                  :margin-bottom "0.5em"}}
    [type-badge-view ki-type]
    [version-picker {:major major
                     :minor minor
                     :versions versions}]]
   [:h1 {:style {:font-size "1.3em"
                 :margin "0.2em 0 0.1em"}}
    ki-name]
   [:div {:style {:color "#888"
                  :font-size "0.8em"
                  :margin-bottom "0.9em"}}
    (or (fmt/utc published-at) "—")]
   [:p {:style {:font-size "1.05em"
                :line-height "1.5"
                :color "#222"
                :margin 0}}
    output-statement]])

(defn- ki-card
  [ki]
  (let [edit @(rf/subscribe [::edit])] (if (:open? edit) [edit-card ki edit] [static-card ki])))

(defn ki-page
  "The KI page: inputs (removable) + add-input control above, the editable card in
  the middle, successors below, joined by directed connectors."
  [{:keys [id inputs successors]
    :as ki}]
  [:div {:style {:display "flex"
                 :flex-direction "column"
                 :align-items "center"
                 :padding "1em 0.6em 2em"}}
   (into [:div {:style {:display "flex"
                        :flex-wrap "wrap"
                        :gap "0.5em"
                        :justify-content "center"
                        :align-items "center"}}]
         (concat
          (for [inp inputs]
            ^{:key (:id inp)}
            [mini-card inp #(rf/dispatch [::drop-input id (select-keys inp [:name :major])])])
          [^{:key "add"} [add-input-control ki]]))
   [connector]
   [ki-card ki]
   (when (seq successors)
     [:<>
      [connector]
      (into [:div {:style {:display "flex"
                           :flex-wrap "wrap"
                           :gap "0.5em"
                           :justify-content "center"}}]
            (for [s successors] ^{:key (:id s)} [mini-card s nil]))])])
