(ns landing.pages.structure-be-test
  (:require
   [clojure.test               :refer [deftest is]]
   [landing.pages.structure-be :as sut]))

(deftest public-page-test
  (is
   (=
    [[:meta {:charset "utf-8"}]
     [:link {:type "text/css"
             :rel "stylesheet"
             :href "/css/w3_schools.css"}]
     [:link {:type "text/css"
             :rel "stylesheet"
             :href "/css/w3_colors_flat.css"}]
     [:link {:type "text/css"
             :rel "stylesheet"
             :href "/css/components.css"}]
     [:link {:type "text/css"
             :rel "stylesheet"
             :href "/custom.css"}]
     [:script {:type "text/javascript"
               :crossorigin "anonymous"
               :src "https://kit.fontawesome.com/4bcf978f75.js"}]
     [:meta {:content "width=device-width,initial-scale=1"
             :name "viewport"}]
     [:meta {:name "title"
             :property "og:title"
             :content "test page"}]
     [:meta {:name "twitter:title"
             :content "test page"}]
     [:title "test page"]
     [:meta {:name "og:type"
             :property "og:type"
             :content "website"}]
     [:link {:rel "icon"
             :href "/favicon.ico"}]
     [:meta {:name "author"
             :content "Hephaistox"}]
     [:meta {:name "description"
             :property "og:description"
             :content "This is a test page"}]
     [:meta {:name "image"
             :property "og:image"
             :content "https://hephaistox.com/img/preview/en.png"}]
     [:meta {:name "og:url"
             :property "og:url"
             :content "https://hephaistox.com"}]
     [:meta {:name "twitter:site"
             :content ""}]
     [:meta {:name "twitter:image"
             :content "https://hephaistox.com/img/preview/en.png"}]
     [:meta {:name "twitter:card"
             :content "summary_large_image"}]
     [:meta {:name "twitter:description"
             :content "This is a test page"}]]
    (sut/public-page-header {} "test page" "This is a test page"))))
