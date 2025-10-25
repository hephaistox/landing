(ns landing.hiccup
  (:require
   [hiccup2.core :refer [html]]))

(defn render-html [el] (str "<!DOCTYPE html>\n" (html {:mode :html} el)))
