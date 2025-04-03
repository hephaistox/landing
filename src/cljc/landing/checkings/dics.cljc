(ns landing.checkings.dics
  "Add here all dictionary entries that need to be checked"
  (:require
   [clojure.set         :as set]
   [landing.language    :refer [possible-langs]]
   [landing.pages.error :as lerror]
   [landing.pages.home  :as lhome]
   [landing.routes      :as lroutes]))

(defn var-to-dic-check
  [var]
  (mapv (fn [[k val]]
          {:dic-entry val
           :id k
           :from (namespace (symbol var))})
        @var))

(def dics-to-check
  (vec (concat (var-to-dic-check #'lhome/dic)
               (var-to-dic-check #'lerror/dic)
               (var-to-dic-check #'lroutes/dic))))

(def validate-dics
  (->> dics-to-check
       (mapv #(let [l (set (keys (:dic-entry %)))
                    possible-langs (set possible-langs)
                    missings (set/difference possible-langs l)
                    unexpecteds (set/difference l possible-langs)]
                (-> %
                    (assoc-in [:tests :missing-languages] (if (empty? missings) :valid missings))
                    (assoc-in [:tests :unexpected-languages]
                              (if (empty? unexpecteds) :valid unexpecteds)))))))
