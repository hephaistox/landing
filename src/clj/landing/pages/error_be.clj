(ns landing.pages.error-be
  (:require
   [clojure.string             :as str]
   [landing.pages.error        :refer [error-body pretty-print-exception]]
   [landing.pages.structure-be :refer [public-page-header]]))

(defn error-page
  "Displays a user friendly message"
  [http-request body-element]
  [:html
   (vec (concat [:head]
                (public-page-header http-request "Hephaistox error page" "This should not happen")))
   (error-body http-request body-element)])

(defn exception-page
  "Display an exception page. Should not happen on production"
  [http-request e]
  [:html
   (vec (concat [:head]
                (public-page-header http-request "Hephaistox error page" "This should not happen")))
   (error-body http-request
               [:div
                [:h1 "An exception happened"]
                (vec (concat [:p]
                             (->> (with-out-str (pretty-print-exception e))
                                  str/split-lines
                                  (mapv
                                   (fn [line] [:span (str/replace line #" " "&nbsp") [:br]])))))])])


(defn page-not-found-page
  "The page is not found"
  [http-request]
  (error-page http-request
              [:div [:h1 "Page not found"] [:p "This path is unknown: `" (:uri http-request) "`"]]))
