(ns landing.checkings.links
  "Add here all links entries that need to be checked:
  - The actual images schema
  - and availability in the webserver"
  (:require
   [landing.pages.structure :as lstructure]
   [landing.routes          :as lroutes]))

(def links-to-check
  (vec (concat (map #(assoc % :from 'landing.pages.routes) (vals lroutes/links))
               (map #(assoc % :from 'landing.pages.structure) (vals lstructure/links)))))
