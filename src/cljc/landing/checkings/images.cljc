(ns landing.checkings.images
  "Add here all images entries that need to be checked:
  - The actual images schema
  - and availability in the webserver"
  (:require
   [landing.pages.home :as lhome]
   [landing.routes     :as lroutes]))

(def images-to-check
  (vec (concat (map #(assoc % :from 'landing.pages.home) (vals lhome/images))
               (map #(assoc % :from 'landing.pages.routes) (vals lroutes/images)))))
