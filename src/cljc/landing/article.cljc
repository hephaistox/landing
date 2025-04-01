(ns landing.article
  "Registry for articles ready to be displayed with [[landing.pages.article]]

  An article should describe a map with `title`, `description`, `handler` where `handler` is a function called with `http-request` and `language` and returns an hiccup compatible element"
  (:require
   [landing.article.contacts   :refer [contacts-map]]
   [landing.article.disclaimer :refer [disclaimer-map]]
   [landing.article.privacy    :refer [privacy-map]]
   [landing.article.simulation :refer [simulation-map]]))

(def article-map
  {"disclaimer" disclaimer-map
   "privacy" privacy-map
   "contact" contacts-map
   "simulation" simulation-map})
