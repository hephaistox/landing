(ns landing.checkings.dics
  "Add here all dictionary entries that need to be checked:
  - The actual languages"
  (:require
   [landing.pages.home :as lhome]
   [landing.routes     :as lroutes]))

(def dics-to-check
  (vec (concat (map #(assoc % :from 'landing.pages.home) (vals lhome/dic))
               (map #(assoc % :from 'landing.pages.routes) (vals lroutes/dic)))))
