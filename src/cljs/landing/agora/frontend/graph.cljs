(ns landing.agora.frontend.graph
  "The publication graph page — a movable/zoomable canvas of a publication's drafts and their 1-hop
  neighbours (from `/agora/api/publication/:id/graph`). Self-contained interaction (pan the canvas,
  wheel to zoom, drag a node) with no graph library: nodes are laid out in DAG layers, rendered as
  mini-cards, connected by SVG edges (predecessor → dependent). A node links to its document."
  (:require
   [landing.agora.frontend.document-page :as dv]
   [landing.agora.frontend.i18n          :as i18n]
   [re-frame.core                        :as rf]
   [reagent.core                         :as r]))

;; --- fetch -----------------------------------------------------------------

(rf/reg-sub ::data (fn [db _] (:agora/graph db)))

(rf/reg-event-db ::data-ok (fn [db [_ resp]] (assoc db :agora/graph (:body resp))))
(rf/reg-event-db ::data-fail
                 (fn [db _]
                   (assoc db
                          :agora/graph
                          {:nodes []
                           :edges []})))

(rf/reg-event-fx ::load
                 (fn [{:keys [db]} [_ cid]]
                   {:db (dissoc db :agora/graph)
                    :fetch {:method :get
                            :url (str "/agora/api/publication/" cid "/graph")
                            :headers {"Accept" "application/json"}
                            :response-content-types {#"application/json" :json}
                            :on-success [::data-ok]
                            :on-failure [::data-fail]}}))

;; --- layout (pure) ---------------------------------------------------------

(def ^:private node-w 190)
;; the card renders at exactly this height (fixed below), so an edge's endpoints (`y` / `y + node-h`)
;; land on the card's real top/bottom edge — no gap under the card
(def ^:private node-h 58)
(def ^:private gap-x 46)
(def ^:private gap-y 74)

(defn- preds-of
  "node id → set of its predecessor ids, from the edges (`{:from pred :to dependent}`)."
  [edges]
  (reduce (fn [m {:keys [from to]}] (update m to (fnil conj #{}) from)) {} edges))

(defn- depth
  "The layer (row) of `id`: its longest predecessor chain within the set. Roots (no predecessor) are 0.
  `seen` guards against a stray cycle."
  [preds id seen]
  (let [ps (get preds id)]
    (if (or (empty? ps) (contains? seen id))
      0
      (inc (apply max 0 (map #(depth preds % (conj seen id)) ps))))))

(defn- adjacency
  "From the edges (pred → dependent): `{:preds {id [pred-ids]} :succs {id [succ-ids]}}`."
  [edges]
  (reduce (fn [a {:keys [from to]}]
            (-> a
                (update-in [:preds to] (fnil conj []) from)
                (update-in [:succs from] (fnil conj []) to)))
          {:preds {}
           :succs {}}
          edges))

(defn- barycentre
  "Mean position of `id`'s `neighbours` within `adj-index` (a neighbouring layer's id → position), or
  nil when the node has no placed neighbour (then it keeps its current spot)."
  [adj-index neighbours]
  (let [xs (keep adj-index neighbours)] (when (seq xs) (/ (reduce + xs) (count xs)))))

(defn- reorder-layer
  "Reorder `layer` (ids) by each node's barycentre among `neighbours-of` in `adj-layer` (the adjacent
  layer's current order); a node with no neighbour keeps its current index."
  [layer neighbours-of adj-layer]
  (let [adj-index (into {} (map-indexed (fn [i id] [id i]) adj-layer))
        cur (into {} (map-indexed (fn [i id] [id i]) layer))]
    (vec (sort-by (fn [id] (or (barycentre adj-index (neighbours-of id)) (cur id))) layer))))

(defn- order-layers
  "Cut edge crossings with a few barycentre sweeps — top-down (order by predecessors), then bottom-up
  (order by successors), alternating."
  [layers {:keys [preds succs]}]
  (let [n (count layers)]
    (reduce
     (fn [ls iter]
       (if (even? iter)
         (reduce (fn [ls i] (assoc ls i (reorder-layer (ls i) #(get preds % []) (ls (dec i)))))
                 ls
                 (range 1 n))
         (reduce (fn [ls i] (assoc ls i (reorder-layer (ls i) #(get succs % []) (ls (inc i)))))
                 ls
                 (reverse (range 0 (dec n))))))
     layers
     (range 6))))

(defn- layout
  "Position every node: `id → {:x :y}`. Nodes sit in DAG layers (predecessors above); within a layer
  they are ordered to reduce edge crossings (barycentre heuristic) and the layer is centred."
  [nodes edges]
  (let [preds (preds-of edges)
        ids (mapv :id nodes)
        depth-of (into {} (map (fn [id] [id (depth preds id #{})])) ids)
        max-l (apply max 0 (vals depth-of))
        init (mapv (fn [l] (vec (sort (filter #(= l (depth-of %)) ids)))) (range (inc max-l)))
        ordered (order-layers init (adjacency edges))
        step-x (+ node-w gap-x)
        widest (apply max 1 (map count ordered))]
    (into {}
          (for [[l layer] (map-indexed vector ordered)
                :let [offset (* (/ (- widest (count layer)) 2) step-x)]
                [i id] (map-indexed vector layer)]
            [id {:x (+ offset (* i step-x))
                 :y (* l (+ node-h gap-y))}]))))

(defn- bounds
  "The `[w h]` the positioned nodes span (for the inner canvas + SVG size)."
  [positions]
  [(+ node-w (apply max 0 (map :x (vals positions))))
   (+ node-h (apply max 0 (map :y (vals positions))))])

;; --- a node --------------------------------------------------------------

(defn- node-card
  "One graph node: a compact card (kind badge, title, error bell) at its position. Dragging it moves
  the node; a click (no drag) opens its document. `on-down` starts a possible drag."
  [node {:keys [x y]} on-down]
  [:div {:on-mouse-down #(on-down node %)
         :style {:position "absolute"
                 :left (str x "px")
                 :top (str y "px")
                 :width (str node-w "px")
                 ;; fixed height so every card is exactly `node-h` tall and edges meet its real edges
                 :height (str node-h "px")
                 :box-sizing "border-box"
                 :padding "0.5em 0.6em"
                 :border (str "1px " (if (:draft node) "dashed #b98a3e" "solid #ddd"))
                 :border-radius "0.5em"
                 :background (if (:in-publication? node) "#fffdf7" "#fff")
                 :box-shadow "0 1px 4px rgba(0,0,0,0.12)"
                 :cursor "grab"
                 :user-select "none"
                 :overflow "hidden"}}
   [:div {:style {:display "flex"
                  :align-items "center"
                  :gap "0.4em"
                  :margin-bottom "0.25em"}}
    [dv/kind-badge
     (some-> (:kind node)
             keyword)]
    [:span {:style {:margin-left "auto"}}
     [dv/error-flag (count (:errors node))]]]
   [:div {:style {:font-weight 600
                  :font-size "0.86em"
                  :line-height 1.25
                  :white-space "nowrap"
                  :overflow "hidden"
                  :text-overflow "ellipsis"}}
    (:title node)]])

;; --- the interactive canvas ------------------------------------------------

(defn- edge-line
  "An SVG edge from `a`'s bottom-centre to `b`'s top-centre: a small dot marks where it leaves the
  source card, a line runs to the target, and an arrow head marks where it enters the target's top."
  [a b]
  (when (and a b)
    (let [x1 (+ (:x a) (/ node-w 2))
          y1 (+ (:y a) node-h)]
      [:g
       [:line {:x1 x1
               :y1 y1
               :x2 (+ (:x b) (/ node-w 2))
               :y2 (:y b)
               :stroke "#b9770e"
               :stroke-width 1.5
               :marker-end "url(#agora-arrow)"}]
       ;; the connection pastille, sitting on the line at the source card's bottom edge
       [:circle {:cx x1
                 :cy y1
                 :r 3.5
                 :fill "#b9770e"}]])))

(defn graph-canvas
  "The pan / zoom / drag surface. Local state: node positions (drag mutates them), pan offset, zoom,
  and the fit-zoom (a floor so the graph can never be zoomed out smaller than the whole thing). Window
  mouse listeners handle the drag while the button is held; a container ref lets us measure the
  viewport to fit and to zoom toward the pointer. A node press-and-release (no move) opens its
  document; a press-and-drag repositions it."
  [_lang _nodes _edges]
  (let [positions (r/atom nil) ; id → {:x :y}
        pan (r/atom {:x 0
                     :y 0})
        zoom (r/atom 1)
        min-zoom (r/atom 0.2) ; the fit-zoom — no dezoom past the whole graph
        container (r/atom nil)
        ;; drag: {:kind :node/:pan, :id, :x0 :y0 (mouse), :ox :oy (start pos/pan), :moved?}
        drag (r/atom nil)
        fit! (fn []
               (when-let [el @container]
                 (let [[w h] (bounds @positions)
                       vw (.-clientWidth el)
                       vh (.-clientHeight el)
                       pad 48
                       z (min 1 (/ (- vw pad) (max 1 w)) (/ (- vh pad) (max 1 h)))]
                   (reset! min-zoom z)
                   (reset! zoom z)
                   (reset! pan {:x (/ (- vw (* w z)) 2)
                                :y (/ (- vh (* h z)) 2)}))))
        on-move (fn [e]
                  (when-let [{:keys [kind id x0 y0 ox oy]} @drag]
                    (let [dx (- (.-clientX e) x0)
                          dy (- (.-clientY e) y0)]
                      (when (or (> (js/Math.abs dx) 3) (> (js/Math.abs dy) 3))
                        (swap! drag assoc :moved? true))
                      (case kind
                        :node (swap! positions assoc
                                id
                                {:x (+ ox (/ dx @zoom))
                                 :y (+ oy (/ dy @zoom))})
                        :pan (reset! pan {:x (+ ox dx)
                                          :y (+ oy dy)})))))
        on-up (fn [_]
                ;; a node pressed and released without moving is a click — open its document
                (let [{:keys [kind moved? node]} @drag]
                  (when (and (= kind :node) (not moved?) node)
                    (rf/dispatch
                     [:agora/goto
                      (i18n/doc-url (name (:lang node)) (name (:type node)) (:id node))])))
                (reset! drag nil))
        start-node (fn [node e]
                     (.stopPropagation e)
                     (let [p (get @positions (:id node))]
                       (reset! drag {:kind :node
                                     :id (:id node)
                                     :x0 (.-clientX e)
                                     :y0 (.-clientY e)
                                     :ox (:x p)
                                     :oy (:y p)
                                     :moved? false
                                     :node node})))
        start-pan (fn [e]
                    (reset! drag {:kind :pan
                                  :x0 (.-clientX e)
                                  :y0 (.-clientY e)
                                  :ox (:x @pan)
                                  :oy (:y @pan)}))
        on-wheel (fn [e]
                   (.preventDefault e)
                   (when-let [el @container]
                     (let [rect (.getBoundingClientRect el)
                           mx (- (.-clientX e) (.-left rect))
                           my (- (.-clientY e) (.-top rect))
                           oz @zoom
                           nz (-> (* oz (if (pos? (.-deltaY e)) 0.9 1.1))
                                  (max @min-zoom)
                                  (min 2.5))
                           ;; keep the graph point under the cursor fixed while zooming
                           gx (/ (- mx (:x @pan)) oz)
                           gy (/ (- my (:y @pan)) oz)]
                       (reset! zoom nz)
                       (reset! pan {:x (- mx (* gx nz))
                                    :y (- my (* gy nz))}))))]
    (r/create-class
     {:display-name "graph-canvas"
      :component-did-mount (fn [_]
                             (.addEventListener js/window "mousemove" on-move)
                             (.addEventListener js/window "mouseup" on-up)
                             ;; fit once the container has real dimensions
                             (js/setTimeout fit! 0))
      :component-will-unmount (fn [_]
                                (.removeEventListener js/window "mousemove" on-move)
                                (.removeEventListener js/window "mouseup" on-up))
      :reagent-render
      (fn [lang nodes edges]
        ;; seed positions once from the layout; drags keep them afterwards
        (when (nil? @positions) (reset! positions (layout nodes edges)))
        (let [pos @positions
              [w h] (bounds pos)]
          [:div {:ref (fn [el] (reset! container el))
                 :on-mouse-down start-pan
                 :on-wheel on-wheel
                 :style {:position "relative"
                         :height "72vh"
                         :overflow "hidden"
                         :border "1px solid #e2ddd2"
                         :border-radius "0.6em"
                         :background "#faf8f3"
                         :cursor "grab"}}
           ;; recenter — outside the transform layer, so it stays put
           [:button {:on-mouse-down #(.stopPropagation %)
                     :on-click (fn [e] (.stopPropagation e) (fit!))
                     :style {:position "absolute"
                             :top "0.6em"
                             :right "0.6em"
                             :z-index 5
                             :border "1px solid #b9770e"
                             :background "#fff"
                             :color "#b9770e"
                             :border-radius "0.3em"
                             :padding "0.3em 0.7em"
                             :font-size "0.8em"
                             :font-weight 600
                             :cursor "pointer"}}
            (str "⤢ " (i18n/t lang :graph/recenter))]
           [:div {:style {:position "absolute"
                          :transform-origin "0 0"
                          :transform
                          (str "translate(" (:x @pan) "px," (:y @pan) "px) scale(" @zoom ")")}}
            [:svg {:width w
                   :height h
                   :style {:position "absolute"
                           :left 0
                           :top 0
                           :pointer-events "none"
                           :overflow "visible"}}
             [:defs
              [:marker {:id "agora-arrow"
                        :viewBox "0 0 10 10"
                        :refX 9
                        :refY 5
                        :markerWidth 6
                        :markerHeight 6
                        :orient "auto-start-reverse"}
               [:path {:d "M 0 0 L 10 5 L 0 10 z"
                       :fill "#b9770e"}]]]
             (for [{:keys [from to]} edges]
               ^{:key (str from "->" to)} [edge-line (get pos from) (get pos to)])]
            (for [node nodes]
              ^{:key (:id node)} [node-card node (get pos (:id node)) start-node])]]))})))

;; --- page ------------------------------------------------------------------

(defn graph-page
  "The publication graph page: a header with a back link to the publication, then the canvas. Fetches
  the subgraph on mount via `::load` (dispatched by core on the route change)."
  [pub-id]
  (let [lang @(rf/subscribe [::i18n/lang])
        {:keys [nodes edges]} @(rf/subscribe [::data])]
    [:div {:style {:max-width "76em"
                   :margin "1.2em auto"
                   :padding "0 0.8em"
                   :font-family "system-ui, sans-serif"}}
     [:div {:style {:display "flex"
                    :align-items "center"
                    :gap "0.8em"
                    :margin-bottom "0.6em"}}
      [:a {:href (i18n/publication lang pub-id)
           :style {:color "#b9770e"
                   :text-decoration "none"
                   :font-weight 600}}
       (str "← " (i18n/t lang :graph/back))]
      [:span {:style {:color "#888"
                      :font-size "0.85em"}}
       (i18n/t lang :graph/drag-hint)]]
     (if (nil? nodes)
       [:p {:style {:color "#aaa"}}
        "…"]
       [graph-canvas lang nodes edges])]))
