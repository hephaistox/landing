(ns landing.article
  "Registry for articles ready to be displayed with [[landing.pages.article]]

  An article should describe a map with `title`, `description`, `handler` where `handler` is a function called with `http-request` and `language` and returns an hiccup compatible element"
  (:require
   [landing.article.contact-validated :refer [contact-validated-map]]
   [landing.article.contacts          :refer [contacts-map]]
   [landing.article.digital-twin      :refer [digital-twin-map]]
   [landing.article.disclaimer        :refer [disclaimer-map]]
   [landing.article.hephaistox        :refer [hephaistox-map]]
   [landing.article.legal-notice      :refer [legal-notice-map]]
   [landing.article.privacy           :refer [privacy-map]]
   [landing.article.project           :refer [project-map]]
   [landing.article.rivalis           :refer [rivalis-map]]
   [landing.article.this-website      :refer [this-website-map]]
   [landing.article.who-are-we        :refer [who-are-we-map]]))

(def article-map
  {"disclaimer" disclaimer-map
   "rivalis" rivalis-map
   "privacy" privacy-map
   "who-are-we" who-are-we-map
   "projets" project-map
   "hephaistox" hephaistox-map
   "legal-notice" legal-notice-map
   "contacts" contacts-map
   "about-site" this-website-map
   "contact-validated" contact-validated-map
   "advanced-solution-for-industries" digital-twin-map})
