(ns landing.article.architecture)

(defn privacy-body
  [_http-request l]
  (case l
    :en [:article.text [:h1 "Your IT and Supply Chain projects"] [:p ""]]
    :fr
    [:article.text
     [:h1 "Vos projets informatique et Supply chain"]
     [:p
      "Notre expertise en projets d'aide à la décision dans l'industrie, nous permet aussi de vous assister dans la maîtrise d'oeuvre et d'ouvrage de vos projets Informatique et Supply Chain."]
     [:h2 "La maîtrise d'oeuvre"]
     [:p
      "Il définit les besoins, les objectifs, les contraintes, et s’assure que le résultat final y réponde."]
     [:ul
      [:li "Rôle : exprimer ce qu’il veut."]
      [:li "Responsable de :"]
      [:ul [:li "la définition du besoin,"] [:li "le budget,"] [:li "les délais,"]]
      [:li "la validation du livrable."]]
     [:h3 "Exemple de réalisation:"]
     [:ul
      [:li "Cadrage d'une plateforme d'intelligence artificielle pour le football."]
      [:li
       "Construire et manager une équipe multi-disciplinaire production / qualité / supply chain pour un projet de référentiel"]
      [:li "Définir le périmètre "]]
     [:h2 "La maîtrise d'ouvrage"]
     [:p
      "C’est l’équipe de réalisation, qui conçoit et met en œuvre la solution technique pour répondre au besoin exprimé par la MOA."]
     [:ul
      [:li "Rôle : dire comment faire et réaliser."]
      [:li "Responsable de :"]
      [:ul
       [:li "la conception technique,"]
       [:li "la réalisation,"]
       [:li "le suivi technique du projet."]]]
     [:h3 "Exemple de réalisation:"]
     [:ul [:li "Acheter un outil de planification"] [:li "Combler une fonction manquante"]]]))

(def architecture-map
  {:title {:en "Your IT and Supply Chain projects"
           :fr "Vos projets informatique et Supply chain"}
   :description {:en "Your IT and Supply Chain projects"
                 :fr "Vos projets informatique et Supply chain"}
   :handler privacy-body})
