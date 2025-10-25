(ns landing.article.this-website
  (:require
   [auto-web.components.img  :refer [cicon]]
   [auto-web.components.link :refer [link-opts]]
   [landing.routes           :as lroutes]))

(comment
  #?(:clj (do (require '[clojure.edn :as edn] '[clojure.java.io :as io])
              (with-open [r (io/reader "../auto_core/deps.edn")]
                (-> (edn/read (java.io.PushbackReader. r))
                    :deps
                    keys
                    count))))
  ;
)

(defn this-website-body
  [_http-request l]
  (case l
    :en
    [[:h1 "About this site"]
     [:p
      "For us, being a craftsman means using, choosing, using, and maintaining our tools with care. Like all our applications, this site has the following properties."]
     [:h2 "Libraries / Dependencies"]
     [:p
      "Few libraries are used to create this site, and they are very small. This is a deliberate choice that implies:"]
     [:ul
      [:li "A reduced attack surface for intrusions."]
      [:li "Complete freedom."]
      [:li "Complete control."]]
     [:p
      "Our library "
      [:a {:href "https://github.com/Heph-archive/automaton-web"}
       "automaton-web"]
      " uses 15 dependencies, while "
      [:a {:href "https://github.com/Heph-archive/automaton-core"}
       "automaton-core"]
      " has 13."]
     [:h2 "The footprint of this site"]
     [:p
      "The size of the website is significantly less than 1 megabyte for the home page, all inclusive. This size is very moderate, making the page load very fast."]
     [:h2 "Dark Mode"]
     [:p "The site is displayed in dark mode if your system settings specify it."]
     [:h2 "Printing"]
     [:p
      "Printing a page of the site removes unnecessary navigation information and adapts the layout."]
     [:h2 "Multi-language"]
     [:p
      "Two versions are available, English and French. Your browser preferences are used to choose the default language for the site. The user can override this choice by clicking on the desired language."]
     [:h2 "Robustness"]
     [:p "All links are defined centrally. Thus, they are testable, and regularly tested."]
     [:h2 "Responsive"]
     [:p
      "From mobile phones, tablets, and large screens, the site adapts its typography and content to suit any screen size, resolution, or orientation..."]
     [:h2 "Source Code"]
     [:p
      "For security reasons, the code is not visible on GitHub, but the libraries used are: "
      [:a (link-opts (:github lroutes/links)) (cicon {} "fa-github fa-brands")]]]
    :fr
    [[:h1 "A propos de ce site"]
     [:p
      "Pour nous, être un artisan implique d'utiliser choisir utiliser et entretenir ses outils avec soin. Comme toutes nos applications, ce site a les propriétés suivantes"]
     [:h2 "Les bibliothèques / dépendances"]
     [:p
      "Peu de bibliothèques sont utilisées pour réaliser ce site, et ce sont de toutes petites bibliothèques. C'est un choix délibéré qui implique :"]
     [:ul
      [:li "Une surface d'attaque aux intrusions diminuée."]
      [:li "Une liberté complète."]
      [:li "Une maîtrise complète."]]
     [:p
      "Notre bibliothèque "
      [:a {:href "https://github.com/Heph-archive/automaton-web"}
       "`automaton-web`"]
      "utilise 15 dépendances, alors que "
      [:a {:href "https://github.com/Heph-archive/automaton-core"}
       "automaton-core"]
      " en compte 13."]
     [:h2 "L'emprunte de ce site"]
     [:p
      "Le poids du site web est sensiblement moins d'1 Méga octets pour la page d'accueil, tout compris. Cette taille est très modérée, elle rends le chargement de la page très rapide."]
     [:h2 "Mode sombre"]
     [:p "Le site s'affiche en mode sombre si vos paramètres systèmes le spécifie."]
     [:h2 "Impression"]
     [:p
      "L'impression d'une page du site supprime les informations de navigation non nécessaires, adapte la mise en page."]
     [:h2 "Le multi-langue"]
     [:p
      "Deux versions sont disponibles, Anglais et Français. Les préférences de votre navigateur sont utilisées pour choisir la langue par défaut du site. L'utilisateur peut contourner ce choix en cliquant sur la langue qu'il désire"]
     [:h2 "Robustesse"]
     [:p
      "La définition de tous les liens est centralisée. Ainsi, ils sont testables, et régulièrement testés."]
     [:h2 "Responsive"]
     [:p
      "Du téléphone portable, la tablette au grand écran, le site adapte sa typographie, son contenu, pour qu'il soit adapté à n'importe quelle taille d'écran, résolution, orientation... "]
     [:h2 "Code source"]
     [:p
      "Pour des raisons de sécurité, le code n'est pas visible sur github, mais les bibliothèques utilisées le sont : "
      [:a (link-opts (:github lroutes/links)) (cicon {} "fa-github fa-brands")]]]))

(def this-website-map
  {:title {:en "About this website"
           :fr "A propos de ce site"}
   :description {:en "About this website"
                 :fr "A propos de ce site"}
   :handler this-website-body})
