(ns landing.article.disclaimer
  (:require
   [landing.routes :refer [mail-txt]]))

(defn disclaimer-body
  [_http-request l]
  (case l
    :fr
    [[:article.text
      [:h1 "Clause de non responsabilité"]
      [:p
       "Tout le contenu de ce site est fourni à titre d'informations seulement. Ce site est mis à jour régulièrement en fonction de notre capacité. Ce site contient des liens vers des sites webs tiers, que vous pouvez visiter à votre gré. A cet égard, Hephaistox n'intervient pas comme intermédiaire, et ne s'interpose pas entre vous et ses tiers."]
      [:p "Hephaistox n'est pas responsable du contenu des sites qu'il réfère."]
      [:p
       "Les droits de propriété intellectuelle concernant les informations fournies sur ce site sont détenus par Hephaistox. Toute infraction de ces droits est défendue."]
      [:p
       [:b "Contact : "]
       "si vous avez des questions à propos de cette clause de non responsabilité, écrivez à "
       (mail-txt :info-mail l)]]]
    :en
    [[:article.text
      [:h1 "Disclaimer"]
      [:p
       "All content on this website is provided purely for information purposes.The website is updated regularly to the best of our ability.This website offers links to third-party websites, which you may visit if you wish. In this regard, Hephaistox does not act as intermediary nor does it mediate in any way whatsoever between you and these third parties."]
      [:p
       "Hephaistox is in no way responsible for the content of the sites to which it provides a link."]
      [:p
       "Intellectual property rights in respect of the information and data provided on this website are held by Hephaistox. Any infringement upon these rights is prohibited."]
      [:p
       [:b "Contact : "]
       "If you have any questions about this disclaimer, please send your email to "
       (mail-txt :info-mail l)]]]))

(def disclaimer-map
  {:title {:en "Disclaimer"
           :fr "Clause de non responsabilité"}
   :description {:en "Disclaimer"
                 :fr "Clause de non responsabilité"}
   :handler disclaimer-body})
