(ns landing.agora.frontend.loading
  "Loading skeletons shown while a page's data is in flight (a KI-page skeleton, a
  discover-grid skeleton, …), chosen by the pending? route kind."
  (:require
   [landing.agora.frontend.document-page :as    dv
                                         :refer [card-style]]))

(defn- skel
  "A shimmering placeholder block (`.agora-skel` / `@keyframes agora-pulse` in the
  ki.html shell)."
  [style]
  [:div {:class "agora-skel"
         :style style}])

(defn- skeleton-card
  "Card-shaped placeholder matching a KI card's silhouette."
  []
  [:div {:style (assoc card-style :display "flex" :flex-direction "column" :gap "0.7em")}
   [skel {:width "9em"
          :height "1.1em"}]
   [skel {:width "70%"
          :height "1.5em"}]
   [skel {:width "6em"
          :height "0.8em"}]
   [skel {:height "0.9em"}]
   [skel {:height "0.9em"}]
   [skel {:width "85%"
          :height "0.9em"}]])

(defn- skeleton-ki-page
  []
  [:div {:style {:display "flex"
                 :flex-direction "column"
                 :align-items "center"
                 :padding "1em 0.6em 2em"}}
   [skeleton-card]])

(defn- skeleton-discover
  []
  [:div {:style {:max-width "72em"
                 :margin "1.5em auto"
                 :padding "0 0.8em"}}
   [skel {:width "22em"
          :max-width "80%"
          :height "1.1em"
          :margin-bottom "1.2em"}]
   (into [:div {:style {:display "grid"
                        :grid-template-columns "repeat(auto-fill, minmax(17em, 1fr))"
                        :gap "0.9em"}}]
         (for [i (range 6)]
           ^{:key i}
           [:div {:style {:display "flex"
                          :flex-direction "column"
                          :gap "0.6em"
                          :min-height "9em"
                          :padding "0.9em 1em"
                          :border "1px solid #e2ddd2"
                          :border-radius "0.6em"
                          :background "#fff"}}
            [skel {:width "6em"
                   :height "1em"}]
            [skel {:width "80%"
                   :height "1.25em"}]
            [skel {:height "0.8em"}]
            [skel {:width "90%"
                   :height "0.8em"}]
            [skel {:width "60%"
                   :height "0.8em"}]]))])

(defn loading-view
  "Skeleton placeholder shown while a page's data loads, matched to the target
  `kind` so the layout does not jump when the content arrives."
  [kind]
  (case kind
    (:home :discover :articles) [skeleton-discover]
    [skeleton-ki-page]))
