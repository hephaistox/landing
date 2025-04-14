(ns landing.article.sasu-caumond)

(defn sasu-caumond-body
  [_http-request l]
  (case l
    :en [:article.text
         [:h1 "SASU caumond"]
         [:p "All this activity is dealt with SASU caumond"]
         [:p "Coordinates are SIREN: 905156402, SIRET:90515640200018."]]
    :fr [:article.text
         [:h1 "SASU caumond"]
         [:p "Cette activité est prise en charge par la SASU caumond"]
         [:p "Les coordonnées de l'entreprise sont SIREN: 905156402, SIRET:90515640200018."]]))

(def sasu-caumond-map
  {:title {:en "SASU caumond"
           :fr "SASU caumond"}
   :description {:en "SASU caumond"
                 :fr "SASU caumond"}
   :handler sasu-caumond-body})
