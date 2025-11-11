(ns landing.endpoints.resource
  "Returns resources in directory `/`."
  (:require
   [reitit.ring          :as rring]
   [ring.middleware.gzip :as ring-gzip]
   [ring.util.response   :as rr]))

(defn resource-handler
  "Resources from directory `/` is returned and zipped if big enough"
  [request]
  (let [h (-> (rring/create-resource-handler {:path "/"})
              ring-gzip/wrap-gzip)]
    (when-let [response (h request)] (rr/header response "Allow-Control-Allow-Origin" "*"))))
