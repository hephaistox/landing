(ns landing.pages.js-example
  "Specific to the website home page"
  (:require
   [landing.pages.structure :refer [csidebar]]))

(def images
  (->> []
       (mapv (fn [link] [(:img-id link) link]))
       (into {})))

(defn body
  [http-request]
  [:body.w3-large.w3-row
   [:div.w3-col.l3 {:style {:height "100vh"}
                    :onclick "document.getElementById(\"menu-sidebar\").style.display = \"block\";"}
    (csidebar {} http-request)]
   [:div#js-example-content.w3-col.m12.l9.s12.w3-flex {:style {:height "100vh"
                                                               :flex-direction "column"}}
    [:div "Loading"]]])
