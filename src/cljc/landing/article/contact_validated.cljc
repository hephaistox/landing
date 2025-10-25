(ns landing.article.contact-validated
  (:require
   [auto-web.components.img  :refer [cimg images]]
   [auto-web.components.link :refer [link-opts]]
   [landing.routes           :refer [links]]))

(defn contact-validated-body
  [_http-request l]
  (case l
    :fr [[:article.text
          [:h1 "Votre contact a été enregistré"]
          [:p "Vous serez contacté dans moins de 48 heures"]
          [:p "Ce sera l'occasion de se découvrir, et de prendre rendez-vous."]
          [:div.w3-center (cimg {:class "w3-center"} :medium (:hephaistox-logo images))]
          [:div.w3-center.w3-padding-large
           [:a
            (link-opts (:home links))
            [:div.w3-button.w3-orange.w3-ripple.w3-text-white.w3-large.w3-bold
             "Continuez de visiter le site"]]]]]
    [[:article.text
      [:h1 "The contact has been recorded"]
      [:p "Thanks for your registration"]
      [:div.w3-center (cimg {:class "w3-center"} :medium (:hephaistox-logo images))]
      [:div.w3-center.w3-padding-large
       [:a
        (link-opts (:home links))
        [:div.w3-button.w3-orange.w3-ripple.w3-text-white.w3-large.w3-bold
         "Continue the website visit"]]]]]))

(def contact-validated-map
  {:title {:en "Contact validated"
           :fr "Contact validé"}
   :description {:en "Page for a validated contact"
                 :fr "Page affichée pour un contact validé"}
   :handler contact-validated-body})
