(ns landing.checkings.links
  "Add here all links entries that need to be checked:
  - The actual images schema
  - and availability in the webserver"
  (:require
   [clojure.string          :as str]
   [landing.pages.structure :as lstructure]
   [landing.routes          :as lroutes]))

(def base-url "http://localhost:8080")

(defn add-base-url
  [url base-url]
  (if (or (str/starts-with? url "mail") (str/starts-with? url "http")) url (str base-url url)))

(def tlds
  {"fr" "https://hephaistox.fr"
   "com" "https://hephaistox.com"})

(def links-to-check
  (vec (concat (map #(assoc % :from 'landing.pages.routes) (vals lroutes/links))
               (map #(assoc % :from 'landing.pages.structure) (vals lstructure/links)))))
