(ns landing.article.legal-notice)

(defn legal-notice-body
  [_http-request l]
  (case l
    :en
    [:section#legal-notice-en
     [:h1 "Legal Notice"]
     [:h2 "Website publisher"]
     [:p
      "This website, accessible at "
      [:a {:href "https://caumond.com"}
       "https://caumond.com"]
      ", is published by:"]
     [:ul
      [:li [:strong "Nom : "] "Anthony Caumond"]
      [:li [:strong "Société : "] "Hephaistox"]
      [:li [:strong "Forme juridique : "] "SASU"]
      [:li [:strong "SIRET : "] "999 469 349 00016"]
      [:li [:strong "RCS : "] "Clermont-Ferrand"]
      [:li [:strong "Numéro de TVA intracommunautaire : "] "FR28 999469349"]
      [:li [:strong "Adresse du siège social : "] "5 rue saint dominique 63350 JOZE"]
      [:li [:strong "Email : "] "anthony@hephaistox.fr"]]
     [:h2 "Publication director"]
     [:p "Anthony Caumond"]
     [:h2 "Hosting"]
     [:p "The website is hosted by:"]
     [:ul
      [:li [:strong "Hébergeur : "] "Clever cloud SAS"]
      [:li [:strong "Adresse : "] "4 rue Voltaire, 44000 Nantes, France"]
      [:li
       [:strong "Mail : "]
       [:a {:href "support@clever-cloud.com"}
        "support@clever-cloud.com"]]
      [:li [:strong "Téléphone : "] "02 85 52 07 69"]]
     [:h2 "Intellectual property"]
     [:p
      "All content available on this website (texts, images, graphics, logo, icons, etc.) "
      "is the exclusive property of Hephaistox unless otherwise stated. "
      "Any reproduction, representation, modification or adaptation, in whole or in part, "
      "is prohibited without prior written authorization."]
     [:h2 "Liability"]
     [:p
      "The publisher strives to provide information that is as accurate as possible. "
      "However, it cannot be held liable for omissions, inaccuracies, or failures to update the content."]
     [:h2 "Personal data"]
     [:p
      "Any information collected via this website is intended solely for Hephaistox. "
      "In accordance with applicable regulations, you have the right to access, rectify, "
      "and delete your personal data."]]
    :fr
    [[:section#legal-notice-fr
      [:h1 "Mentions légales"]
      [:h2 "Éditeur du site"]
      [:p
       "Le présent site, accessible à l’adresse "
       [:a {:href "https://caumond.com"}
        "https://caumond.com"]
       ", "
       [:a {:href "https://caumond.fr"}
        "https://caumond.fr"]
       ", "
       [:a {:href "https://hephaistox.com"}
        "https://hephaistox.com"]
       ", "
       [:a {:href "https://hephaistox.fr"}
        "https://hephaistox.fr"]
       ", est édité par :"]
      [:ul
       [:li [:strong "Nom : "] "Anthony Caumond"]
       [:li [:strong "Société : "] "Hephaistox"]
       [:li [:strong "Forme juridique : "] "SASU"]
       [:li [:strong "SIRET : "] "999 469 349 00016"]
       [:li [:strong "RCS : "] "Clermont-Ferrand"]
       [:li [:strong "Numéro de TVA intracommunautaire : "] "FR28 999469349"]
       [:li [:strong "Adresse du siège social : "] "5 rue saint dominique 63350 JOZE"]
       [:li [:strong "Email : "] "anthony@hephaistox.fr"]]
      [:h2 "Directeur de la publication"]
      [:p "Anthony Caumond"]
      [:h2 "Hébergement"]
      [:p "Le site est hébergé par :"]
      [:ul
       [:li [:strong "Hébergeur : "] "Clever cloud SAS"]
       [:li [:strong "Adresse : "] "4 rue Voltaire, 44000 Nantes, France"]
       [:li
        [:strong "Mail : "]
        [:a {:href "support@clever-cloud.com"}
         "support@clever-cloud.com"]]
       [:li [:strong "Téléphone : "] "02 85 52 07 69"]]
      [:h2 "Propriété intellectuelle"]
      [:p
       "L’ensemble du contenu présent sur ce site (textes, images, graphismes, logo, icônes, etc.) "
       "est la propriété exclusive de Hephaistox, sauf mentions contraires. "
       "Toute reproduction, représentation, modification ou adaptation, totale ou partielle, "
       "est interdite sans autorisation préalable écrite."]
      [:h2 "Responsabilité"]
      [:p
       "L’éditeur s’efforce de fournir sur le site des informations aussi précises que possible. "
       "Toutefois, il ne saurait être tenu responsable des omissions, des inexactitudes ou des carences "
       "dans la mise à jour."]
      [:h2 "Données personnelles"]
      [:p
       "Les informations éventuellement recueillies via le site sont destinées exclusivement à Hephaistox. "
       "Conformément à la réglementation en vigueur, vous disposez d’un droit d’accès, de rectification "
       "et de suppression des données vous concernant."]]]))

(def legal-notice-map
  {:title {:en "Legal notice"
           :fr "Mentions légales"}
   :description {:en "Describe the company supporting that activities"
                 :fr "Décrit l'entreprise qui supporte ces activités"}
   :handler legal-notice-body})
