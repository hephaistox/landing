(ns landing.article.legal-notice)

(defn legal-notice-body
  [_http-request l]
  (case l
    :en [[:h1 "SASU caumond"] [:p "All this activity is dealt with company under creation"]]
    :fr [[:h1 "SASU caumond"]
         [:p "Cette activité est prise en charge par une société en cours de crétaion"]]))

(def legal-notice-map
  {:title {:en "Legal notice"
           :fr "Mentions légales"}
   :description {:en "Describe the company supporting that activities"
                 :fr "Décrit l'entreprise qui supporte ces activités"}
   :handler legal-notice-body})
