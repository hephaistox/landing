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
    (-> request
        h
        (rr/header "Allow-Control-Allow-Origin" "*"))))


