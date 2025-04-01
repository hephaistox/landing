(ns landing.checkings
  "Check web site main data validation"
  (:require
   [auto-web.components.v-img  :refer [validate-img-schema]]
   [auto-web.components.v-link :refer [validate-link-schema]]
   [clojure.set                :as set]
   [landing.checkings.dics     :refer [dics-to-check]]
   [landing.checkings.images   :refer [images-to-check]]
   [landing.checkings.links    :refer [links-to-check]]
   [landing.language           :refer [possible-langs]]))

(defn validate-images
  []
  (->> images-to-check
       (mapv #(-> %
                  (assoc-in [:tests :schema] (or (validate-img-schema %) :valid))
                  (assoc-in
                   [:tests :available-online]
                   (try (slurp (:url %)) :valid (catch Exception _e {:missing (:url %)})))))))


(def validate-dics
  (->> dics-to-check
       (mapv #(let [l (disj (set (keys %)) :from)
                    possible-langs (set possible-langs)
                    missings (set/difference possible-langs l)
                    unexpecteds (set/difference l possible-langs)]
                (-> %
                    (assoc-in [:tests :missing-languages] (if (empty? missings) :valid missings))
                    (assoc-in [:tests :unexpected-languages]
                              (if (empty? unexpecteds) :valid unexpecteds)))))))

(defn validate-links
  []
  (->> links-to-check
       (mapv #(-> %
                  (assoc-in [:tests :schema] (or (validate-link-schema %) :valid))
                  (assoc-in [:tests :available-online]
                            (try (println "checking url:" (:url %))
                                 (when-not (:skip-test? %) (slurp (:url %)))
                                 :valid
                                 (catch Exception _e {:missing (:url %)})))))))
