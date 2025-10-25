(ns landing.endpoints.html.error-be
  (:require
   [auto-web.components.img                :refer [cimg images]]
   [clojure.string                         :as str]
   [env]
   [landing.endpoints.html.public-pages-be :refer [public-page-header]]
   [landing.hiccup                         :refer [render-html]]
   [landing.pages.error                    :refer [error-body error-dic pretty-print-exception]]))

(defn- error-page
  "Displays a user friendly message"
  [http-request body-element]
  (let [l (:lang http-request)
        tr #(get-in error-dic [% l])]
    [:html {:lang (:lang http-request)}
     (vec (concat [:head]
                  (public-page-header http-request (tr :error-page) (tr :should-not-happen))))
     (error-body http-request body-element)]))

(defn exception-page
  "Display an exception page. Should not happen on production"
  [http-request e]
  (let [l (:lang http-request)
        tr #(get-in error-dic [% l])
        e (if (= :dev env/env)
            e
            {:cause (ex-cause e)
             :message (ex-message e)
             :data (ex-data e)})]
    [:html {:lang (:lang http-request)}
     (vec (concat [:head]
                  (public-page-header http-request (tr :error-page) (tr :should-not-happen))))
     (error-body http-request
                 [:div
                  [:h1 "An exception happened"]
                  (vec (concat [:p]
                               (->> (with-out-str (pretty-print-exception e))
                                    str/split-lines
                                    (mapv
                                     (fn [line] [:span (str/replace line #" " " ") [:br]])))))])]))

(defn exception-response
  [http-request e]
  {:status 500
   :headers {"content-type" "text/html"}
   :body (render-html (exception-page http-request e))})

(defn page-not-found-page
  "The page is not found"
  [http-request]
  (let [l (:lang http-request)
        tr #(get-in error-dic [% l])]
    (error-page http-request
                [:div [:h1 (tr :page-not-found)] (cimg {} :full (:not-found images))])))

(defn page-not-found-response
  [http-request]
  {:status 404
   :headers {"content-type" "text/html"}
   :body (render-html (page-not-found-page http-request))})
