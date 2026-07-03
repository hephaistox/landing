(ns landing.agora.frontend.article-view
  "Presentational component for a single article (#31): title, publication
  timestamp and body. The body is plain text; blank lines separate paragraphs."
  (:require
   [clojure.string             :as str]
   [landing.agora.frontend.fmt :as fmt]))

(defn article-card
  "Render one article map (as returned by GET /api/article/:id)."
  [{:keys [title body published-at]}]
  [:article {:style {:width "44em"
                     :max-width "100%"
                     :box-sizing "border-box"
                     :margin "1em auto"
                     :padding "1.5em"
                     :font-family "system-ui, sans-serif"}}
   [:h1 {:style {:font-size "1.8em"
                 :line-height "1.2"
                 :margin "0 0 0.2em"}}
    title]
   [:div {:style {:color "#888"
                  :font-size "0.85em"
                  :margin-bottom "1.3em"}}
    (or (fmt/utc published-at) "—")]
   (into [:div {:style {:font-size "1.05em"
                        :line-height "1.65"
                        :color "#222"}}]
         (for [para (str/split (or body "") #"\n\n+")
               :when (not (str/blank? para))]
           [:p {:style {:margin "0 0 1em"}}
            para]))])
