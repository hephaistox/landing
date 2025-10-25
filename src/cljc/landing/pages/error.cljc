(ns landing.pages.error
  (:require
   #?(:clj [clojure.pprint :refer [pprint]]
      :cljs [cljs.pprint :refer [pprint]])
   [landing.pages.structure :refer [cfooter cheader csidebar]]))

(def error-dic
  {:error-page {:fr "Erreur inattendue"
                :en "Unexpected error"}
   :page-not-found {:fr "Page non trouvée"
                    :en "Page not found"}
   :should-not-happen {:fr "Cela ne devrait pas arriver"
                       :en "This should not happen"}})

(defn pretty-print-exception
  [e]
  (let [exception-data #?(:clj (if (instance? Throwable e) (Throwable->map e) e)
                          :cljs {:message (.-message e)
                                 :name (.-name e)
                                 :stack (.-stack e)})]
    (pprint exception-data)))

(defn error-body
  [http-request body-element]
  [:body.w3-large.w3-row
   [:div.w3-col.m3.l3.w3-row
    [:p.w3-hide-small.w3-hide-medium {:style {:height "1px"
                                              :margin-bottom "1px"
                                              :margin-top "1px"}}]
    (csidebar {:style {:height "100vh"}
               :class "w3-col l3"}
              http-request)]
   [:div#article-content.w3-col.m12.l9.s12
    (cheader {:class "w3-margin"} http-request)
    [:div.w3-content {:style {:min-height "75vh"}}
     body-element]
    (cfooter {} http-request)]])
