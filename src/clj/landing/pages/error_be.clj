(ns landing.pages.error-be
  (:require
   [auto-web.components.img    :refer [cimg images]]
   [clojure.string             :as str]
   [hiccup2.core               :refer [html]]
   [landing.pages.error        :refer [dic error-body pretty-print-exception]]
   [landing.pages.structure-be :refer [public-page-header]]))

(defn- render
  [el]
  (str "<!DOCTYPE html>\n"
       (html {:mode :html
              :escape-strings? false}
             el)))

(defn- error-page
  "Displays a user friendly message"
  [http-request body-element]
  (let [l (:lang http-request)
        tr #(get-in dic [% l])]
    [:html {:lang (:lang http-request)}
     (vec (concat [:head]
                  (public-page-header http-request (tr :error-page) (tr :should-not-happen))))
     (error-body http-request body-element)]))

(defn exception-page
  "Display an exception page. Should not happen on production"
  [http-request e]
  (let [l (:lang http-request)
        tr #(get-in dic [% l])]
    [:html {:lang (:lang http-request)}
     (vec (concat [:head]
                  (public-page-header http-request (tr :error-page) (tr :should-not-happen))))
     (error-body
      http-request
      [:div
       [:h1 "An exception happened"]
       (vec (concat [:p]
                    (->> (with-out-str (pretty-print-exception e))
                         str/split-lines
                         (mapv (fn [line] [:span (str/replace line #" " "&nbsp") [:br]])))))])]))

(defn exception-response
  [http-request e]
  {:status 500
   :headers {"content-type" "text/html"}
   :body (render (exception-page http-request e))})

(defn page-not-found-page
  "The page is not found"
  [http-request]
  (let [l (:lang http-request)
        tr #(get-in dic [% l])]
    (error-page http-request
                [:div [:h1 (tr :page-not-found)] (cimg {} :full (:not-found images))])))

(defn page-not-found-response
  [http-request]
  {:status 404
   :headers {"content-type" "text/html"}
   :body (render (page-not-found-page http-request))})
