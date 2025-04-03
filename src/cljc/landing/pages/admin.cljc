(ns landing.pages.admin
  (:require
   [auto-web.components.badge :refer [cspinner]]))

(defn admin-body
  [_http-request]
  [:body.w3-xlarge.w3-panel.w3-animate-opacity {:style {:user-select "none"}}
   [:h1 "Administration page"]
   [:div#admin-panel (cspinner {})]])
