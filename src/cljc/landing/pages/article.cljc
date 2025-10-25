(ns landing.pages.article
  "The structure of page to display an article.

  The page has an header and a footer at least at the bottom of the viewport, or even deeper if the page is bigger."
  (:require
   [landing.pages.structure :refer [cfooter cheader csidebar]]))

(def vertical-separator
  [:div.w3-col.l1.w3-hide-medium.w3-hide-small {:style {:height "2em"}}])

(defn lsection
  [title subtitle img & content]
  [:div.w3-row.w3-row-padding
   [:div.w3-padding-48.w3-content.w3-animate-opacity {:style {:max-width "1000px"}}
    ;; For section header
    [:div.w3-col.w3-row {:style {:padding-bottom "2em"}}
     vertical-separator
     [:div.w3-col.l2.w3-flex.l3 {:style {:flex-direction "column"}}
      vertical-separator
      img]
     vertical-separator
     [:div.w3-col.m12.l8
      (when title [:h1.w3-left-align.w3-bold.text.w3-text-red.w3-xxxlarge.w3-animate-zoom title])
      (when subtitle
        [:h2.w3-left-alin.w3-bold.text.w3-xxlarge.w3-text-orange.w3-animate-zoom subtitle])]]
    [:div.w3-clear]
    content]])

(defn rsection
  [title subtitle img & content]
  [:div.w3-row.w3-row-padding.w3-flat-midnight-blue
   [:div.w3-padding-48.w3-content.w3-animate-opacity {:style {:max-width "1000px"}}
    ;; For section header
    [:div.w3-row {:style {:padding-bottom "2em"}}
     [:div.w3-col.m12.l8
      (when title [:h1.w3-left-align.w3-bold.text.w3-text-red.w3-xxxlarge.w3-animate-zoom title])
      (when subtitle
        [:h2.w3-left-align.w3-bold.text.w3-xxlarge.w3-text-orange.w3-animate-zoom subtitle])]
     vertical-separator
     [:div.w3-col.w3-flex.l2 {:style {:flex-direction "column"}}
      vertical-separator
      img]]
    [:div.w3-clear]
    content]])

(defn body
  [http-request body-elements]
  [:body.w3-large.w3-row
   [:div#left-menu-placeholder.w3-col.m3.l2
    [:p.w3-hide-small.w3-hide-medium {:style {:height "1px"
                                              :margin-bottom "1px"
                                              :margin-top "1px"}}]
    (csidebar {:class "l2"
               :style {:z-index 0}}
              http-request)]
   [:div#article-content.w3-col.s12.m12.l10
    (cheader {:class "w3-margin"} http-request)
    [:div
     (vec (concat [:article.text.w3-main.w3-row {:style {:min-height "100vh"}}]
                  body-elements))]]
   (cfooter {:style {:height "10em"}
             :class "w3-col"}
            http-request)])
