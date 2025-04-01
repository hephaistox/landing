(ns landing.pages.home-be
  (:require
   [landing.pages.home         :refer [body]]
   [landing.pages.structure-be :refer [public-page-header]]))

(defn home-page
  [http-request]
  [:html
   (vec (concat [:head]
                (public-page-header http-request "Hephaistox home page" "Hephaistox home page")))
   (body http-request)])
