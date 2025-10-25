(ns landing.article.contacts
  (:require
   [auto-web.components.list :refer [cbullet]]
   [landing.routes           :as lroutes]))

(defn contacts-body
  [_http-request l]
  (case l
    :en
    [[:h1 "Contact us"]
     [:p
      "We will be able to adapt our explanation, our examples to your industry if we have the opportunity to discuss them with you. Don't hesitate."]
     (cbullet {}
              [(assoc (:linkedin lroutes/social) :desc "  You can contact us on linked-in")
               (assoc (:github lroutes/social)
                      :desc
                      "  As developers you can see and interact with us on Github")
               (assoc (:mail lroutes/social) :desc "  Send an email")])]
    :fr
    [[:h1 "Contactez-nous"]
     [:p
      "Nous pouvons adaptez nos explications ou nos exemples à votre industrie si nous avons l'opportunité de discutez avec vous. N'hésitez pas."]
     (cbullet {}
              [(assoc (:linkedin lroutes/social) :desc " Suivez nos publications sur LinkedIn")
               (assoc (:github lroutes/social)
                      :desc
                      "  En tant que développeurs, vous pourrez interagir avec nous sur Github")
               (assoc (:mail lroutes/social) :desc " Envoyez-nous un email")])]))

(def contacts-map
  {:title {:en "Contacts"
           :fr "Contacts"}
   :description {:en "Simulation"
                 :fr "Two-minute pitch"}
   :handler contacts-body})
