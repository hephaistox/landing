(ns landing.checkings
  "Landing validation"
  (:require
   [auto-core.schema         :refer [validate-data-humanize]]
   [auto-web.components.img  :refer [img-schema]]
   [auto-web.components.link :refer [link-schema]]
   [clj-http.client          :as http-client]
   [landing.checkings.images :refer [images-to-check]]
   [landing.checkings.links  :refer [add-base-url links-to-check]]))

(defn validate-images
  [base-url]
  (->> images-to-check
       (mapv #(-> %
                  (assoc-in [:tests :schema] (or (validate-data-humanize (img-schema %)) :valid))
                  (assoc-in [:tests :available-online]
                            (try (slurp (str base-url (:url %)))
                                 :valid
                                 (catch Exception e
                                   {:missing (:url %)
                                    :error e})))))))

(defn validate-links
  [base-url]
  (->> links-to-check
       (mapv #(let [url (add-base-url (:url %) base-url)]
                (-> %
                    (assoc-in [:tests :schema]
                              (or (validate-data-humanize (link-schema %) %) :valid))
                    (assoc-in [:tests :available-online]
                              (try (if (:skip-test? %) :skiped (do (slurp url) :valid))
                                   (catch Exception _e {:missing :url}))))))))

(defn http-get
  [url base-url]
  (try (let [url (add-base-url url base-url)] (http-client/get url))
       (catch Exception e {:exception e})))

(defn ping [base-url] (= [200 "pong"] ((juxt :status :body) (http-get "/ping" base-url))))
