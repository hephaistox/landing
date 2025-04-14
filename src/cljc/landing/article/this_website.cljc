(ns landing.article.this-website
  (:require
   [auto-web.components.img  :refer [cicon]]
   [auto-web.components.link :refer [cspan-link]]
   [landing.routes           :as lroutes]))

(defn this-website-body
  [_http-request l]
  (case l
    :en [:article.text [:h1 "About this website"]]
    :fr
    [:article.text
     [:h1 "A propos de ce site"]
     [:p
      "Pour nous, être un artisan implique d'utiliser choisir utiliser et entretenir ses outils avec soin. Comme toutes nos applications, ce site a les propriétés suivantes"]
     [:h2 "Les bibliothèques / dépendances"]
     [:p
      "Peu de bibliothèques sont utilisées pour réaliser ce site. C'est un choix délibéré qui implique:"]
     [:ul
      [:li "Une surface d'attaque aux intrusions diminuée."]
      [:li "Une liberté complète."]
      [:li "Une maîtrise complète."]]
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
      "Deux versions sont disponibles, Anglais et Français. Les préférences de votre navigateur sont utilisées pour choisir la langue par défaut du site. L'utilisateur peut contourner ce choix qui sera stocké dans votre `local storage`. La traduction est réalisée sur le frontend, pas seulement le texte mais certaines image ou document lié."]
     [:h2 "Robustesse"]
     [:p "La définition de tous les liens est centralisée. Ainsi, ils sont testables."]
     [:h2 "Responsive"]
     [:p
      "Du téléphone portable, la tablette au grand écran, le site adapte sa typographie, son contenu."]
     [:h2 "Code source"]
     [:p
      "Tout le code de ce site est visible sur github: "
      (cspan-link {} (:github lroutes/links) (cicon {} "fa-github fa-brands"))]]))

(def this-website-map
  {:title {:en "About this website"
           :fr "A propos de ce site"}
   :description {:en "About this website"
                 :fr "A propos de ce site"}
   :handler this-website-body})
