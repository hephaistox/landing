(ns landing.pages.article
  "The structure of page to display an article.

  The page has an header and a footer at least at the bottom of the viewport, or even deeper if the page is bigger."
  (:require
   [landing.pages.structure :refer [footer header sidebar]]))

(defn body
  [http-request body-element]
  [:body.w3-large.w3-row
   [:div.w3-col.m3.l3.w3-row
    [:p.w3-hide-small.w3-hide-medium {:style {:height "1px"
                                              :margin-bottom "1px"
                                              :margin-top "1px"}}]
    (sidebar http-request
             {:style {:height "97vh"}
              :class "w3-col l3"})]
   [:div#article-content.w3-col.m12.l9.s12
    (header http-request
            {:style {}
             :class "w3-margin"})
    [:div.w3-row
     [:div.w3-panel.w3-main {:style {:min-height "75vh"}}
      [:div.w3-col.m1.w3-hide-small [:p]]
      [:div.w3-col.m10 body-element]
      [:div.w3-col.m1.w3-hide-small [:p]]]
     (footer http-request)]]])
