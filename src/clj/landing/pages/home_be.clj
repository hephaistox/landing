(ns landing.pages.home-be
  (:require
   [hiccup2.core               :refer [html]]
   [landing.pages.home         :refer [body dic]]
   [landing.pages.structure-be :refer [public-page-header]]))

(defn- render [el] (str "<!DOCTYPE html>\n" (html {:mode :html} el)))

(defn home-page
  [http-request]
  (let [l (:lang http-request)
        tr #(get-in dic [% l])]
    [:html {:lang (:lang http-request)}
     (vec (concat [:head] (public-page-header http-request (tr :home-page) (tr :desc))))
     (body http-request)]))

(defn home-response
  [http-request]
  {:status 200
   :headers {"content-type" "text/html"
             "Access-Control-Allow-Origin" "*"}
   :body (render (home-page http-request))})
