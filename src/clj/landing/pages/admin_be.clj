(ns landing.pages.admin-be
  (:require
   [auto-web.page.builder      :refer [js-script-link]]
   [hiccup2.core               :refer [html]]
   [landing.pages.admin        :refer [admin-body]]
   [landing.pages.structure    :refer [links]]
   [landing.pages.structure-be :refer [public-page-header]]))

(defn- render [el] (str "<!DOCTYPE html>\n" (html {:mode :html} el)))

(defn admin-page
  "Displays a user friendly message"
  [http-request]
  [:html {:lang (:lang http-request)}
   (vec (concat [:head]
                (public-page-header http-request "Hephaistox error page" "This should not happen")))
   (admin-body http-request)
   (js-script-link (:reframe-admin links))])

(defn admin-response
  [http-request]
  {:status 200
   :headers {"content-type" "text/html"
             "Access-Control-Allow-Origin" "*"}
   :body (render (admin-page http-request))})
