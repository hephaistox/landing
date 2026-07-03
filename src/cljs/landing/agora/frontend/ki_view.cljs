(ns landing.agora.frontend.ki-view
  "Presentational component for a single Knowledge Item (#46).

  Pure view: takes a KI map (as returned by GET /api/ki/:id) and renders its
  name, a type badge, the publication timestamp, and the output statement. No
  navigation, no links, no edges — those arrive in later slices.")

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

(defn- neighbour-link
  "A link to another KI's lab page, showing its type badge, name and version."
  [{ki-id :id ki-name :name ki-type :type :keys [major minor]}]
  [:a {:href (str "/lab/ki/" ki-id)
       :style {:display "inline-flex"
               :align-items "center"
               :gap "0.4em"
               :text-decoration "none"
               :color "inherit"
               :padding "0.25em 0.55em"
               :border "1px solid #ddd"
               :border-radius "0.3em"}}
   [type-badge-view ki-type]
   [:span ki-name]
   [:span {:style {:color "#aaa"
                   :font-size "0.75em"
                   :font-family "monospace"}}
    (str "v" major "." minor)]])

(defn- neighbour-section
  "Labelled row of neighbour links, or a muted 'none' when empty."
  [label items]
  [:div {:style {:margin-top "1em"}}
   [:div {:style {:font-size "0.72em"
                  :text-transform "uppercase"
                  :letter-spacing "0.06em"
                  :color "#888"
                  :margin-bottom "0.4em"}}
    label]
   (if (seq items)
     (into [:div {:style {:display "flex"
                          :flex-wrap "wrap"
                          :gap "0.4em"}}]
           (for [n items]
             ^{:key (:id n)} [neighbour-link n]))
     [:span {:style {:color "#bbb"
                     :font-style "italic"
                     :font-size "0.85em"}}
      "none"])])

(defn ki-card
  "Render one Knowledge Item with its input and successor links."
  [{ki-name :name ki-type :type
    :keys [major minor published-at output-statement inputs successors]}]
  [:article {:style {:max-width "40em"
                     :margin "1em 0"
                     :padding "1.25em 1.5em"
                     :border "1px solid #ddd"
                     :border-radius "0.5em"
                     :background "#fff"
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
    output-statement]
   [neighbour-section "Inputs — KIs that imply this" inputs]
   [neighbour-section "Successors — KIs this implies" successors]])
