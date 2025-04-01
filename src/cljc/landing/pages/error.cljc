(ns landing.pages.error
  (:require
   #?(:clj [clojure.pprint :refer [pprint]]
      :cljs [cljs.pprint :refer [pprint]])
   [landing.pages.structure :refer [footer header]]))

(defn pretty-print-exception
  [e]
  (let [exception-data #?(:clj (if (instance? Throwable e) (Throwable->map e) e)
                          :cljs {:message (.-message e)
                                 :name (.-name e)
                                 :stack (.-stack e)})]
    (pprint exception-data)))

(defn error-body
  [http-request body-element]
  (let [top-height 12
        bottom-height 12
        in-em #(str % "em")]
    [:body.w3-large.w3-row {:height "100%"}
     [:div#article-content
      (header http-request (in-em top-height))
      [:div.w3-content {:style {:max-width "60vw"
                                :padding-bottom (in-em bottom-height)}}
       body-element]
      (footer http-request)]]))
