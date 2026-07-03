(ns landing.agora.frontend.ki-view
  "Presentational components for a single Knowledge Item and its immediate graph
  neighbourhood. Layout mirrors the flow of implication vertically: input KIs as
  mini cards above, a directed connector, the full KI card in the middle, another
  directed connector, and successor KIs as mini cards below. Each mini card links
  to that KI's own page.")

(def ^:private type-badge
  "Display label + accent colour per KI type."
  {"derived"          {:label "Derived"          :bg "#2c5aa0"}
   "verifiable-claim" {:label "Verifiable claim" :bg "#0b7285"}
   "postulate"        {:label "Postulate"        :bg "#6741d9"}
   "stance"           {:label "Stance"           :bg "#b9770e"}
   "belief"           {:label "Belief"           :bg "#2b8a3e"}
   "credo"            {:label "Credo"            :bg "#c92a2a"}})

(defn- format-utc
  "ISO-8601 UTC string (e.g. \"2026-07-02T00:00:00Z\") -> \"2026-07-02 00:00 UTC\"."
  [iso]
  (when (and (string? iso) (>= (count iso) 16))
    (str (subs iso 0 10) " " (subs iso 11 16) " UTC")))

(defn type-badge-view
  [ki-type]
  (let [{:keys [label bg]} (get type-badge ki-type {:label (or ki-type "?") :bg "#666"})]
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

(defn- mini-card
  "A compact, clickable card for a neighbour KI: type badge, version and name.
  Links to that KI's own lab page."
  [{ki-id :id ki-name :name ki-type :type :keys [major minor]}]
  [:a {:href (str "/lab/ki/" ki-id)
       :style {:display "block"
               :box-sizing "border-box"
               :width "16em"
               :max-width "100%"
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
    [type-badge-view ki-type]
    [:span {:style {:color "#aaa"
                    :font-size "0.7em"
                    :font-family "monospace"}}
     (str "v" major "." minor)]]
   [:div {:style {:font-weight 600
                  :font-size "0.9em"}}
    ki-name]])

(defn- mini-card-row
  "A centered, wrapping row of neighbour mini cards."
  [neighbours]
  (into [:div {:style {:display "flex"
                       :flex-wrap "wrap"
                       :gap "0.6em"
                       :justify-content "center"}}]
        (for [n neighbours]
          ^{:key (:id n)} [mini-card n])))

(defn- connector
  "A short vertical directed link (arrow points down, the direction implication
  flows: inputs imply this KI, which implies its successors)."
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

(defn ki-card
  "The main Knowledge Item card: type badge, version, name, timestamp, statement."
  [{ki-name :name ki-type :type
    :keys [major minor published-at output-statement]}]
  [:article {:style {:width "40em"
                     :max-width "100%"
                     :box-sizing "border-box"
                     :padding "1.25em 1.5em"
                     :border "1px solid #ccc"
                     :border-radius "0.5em"
                     :background "#fff"
                     :box-shadow "0 1px 3px rgba(0,0,0,0.06)"
                     :font-family "system-ui, sans-serif"}}
   [:div {:style {:display "flex"
                  :align-items "center"
                  :gap "0.75em"
                  :margin-bottom "0.5em"}}
    [type-badge-view ki-type]
    [:span {:style {:color "#888"
                    :font-size "0.8em"
                    :font-family "monospace"}}
     (str "v" major "." minor)]]
   [:h1 {:style {:font-size "1.3em"
                 :margin "0.2em 0 0.1em"}}
    ki-name]
   [:div {:style {:color "#888"
                  :font-size "0.8em"
                  :margin-bottom "0.9em"}}
    (or (format-utc published-at) "—")]
   [:p {:style {:font-size "1.05em"
                :line-height "1.5"
                :color "#222"
                :margin 0}}
    output-statement]])

(defn ki-page
  "The KI card with its inputs as mini cards above and successors as mini cards
  below, joined by directed connectors."
  [{:keys [inputs successors] :as ki}]
  [:div {:style {:display "flex"
                 :flex-direction "column"
                 :align-items "center"
                 :padding "1em 0.6em 2em"}}
   (when (seq inputs)
     [:<>
      [mini-card-row inputs]
      [connector]])
   [ki-card ki]
   (when (seq successors)
     [:<>
      [connector]
      [mini-card-row successors]])])
