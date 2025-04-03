(ns landing.article.contacts
  (:require
   [auto-web.components.button :refer [clink-button]]
   [auto-web.components.list   :refer [cbullet]]
   [landing.routes             :as lroutes]))

;;TODO Check on-click de la page de garde
;;TODO Check language ckick
;;TODO Stash simulation
;;TODO Scan pages on Validator

(defn simulation-body
  [_http-request l]
  (case l
    :en
    [:article.text
     [:h1 "Contact us"]
     [:p
      "We will be able to adapt our explanation, our examples to your industry if we have the opportunity to discuss them with you. Don't hesitate."]
     (cbullet {}
              [(assoc (:linkedin lroutes/social) :desc "  You can contact us on linked-in")
               (assoc (:github lroutes/social)
                      :desc
                      "  As developpers you can see and interact with us on Github")
               (assoc (:mail lroutes/social) :desc "  Send an email")])
     [:div
      "or simply book a meeting: "
      [:p.w3-center
       (clink-button {:class "w3-btn w3-orange w3-text-white w3-text-bold w3-round w3-ripple"}
                     "Book a meeting"
                     (get-in lroutes/social [:meeting :link]))]]]
    :fr
    [:article.text
     [:h1 "Contactez-nous"]
     [:p
      "Nous pouvons adaptez nos explications ou nos exemples à votre industrie si nous avons l'opportunité de discutez avec vous. N'hésitez pas."]
     (cbullet {}
              [(assoc (:linkedin lroutes/social) :desc "  Vous pouvez nous contacter sur LinkedIn")
               (assoc (:github lroutes/social)
                      :desc
                      "  En tant que développeurs, vous pourrez interagir avec nous sur Github")
               (assoc (:mail lroutes/social) :desc "  nous envoyer un email")])
     [:div
      "Ou simplement organiser un rendez-vous:"
      [:p.w3-center
       (clink-button {:class "w3-btn w3-orange w3-text-white w3-text-bold w3-round w3-ripple"}
                     "Réservez"
                     (get-in lroutes/social [:meeting :link]))]]]))

(def contacts-map
  {:title {:en "Contacts"
           :fr "Contacts"}
   :description {:en "Simulation"
                 :fr "Two-minute pitch"}
   :handler simulation-body})
