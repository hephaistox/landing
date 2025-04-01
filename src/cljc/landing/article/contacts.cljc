(ns landing.article.contacts
  (:require
   [auto-web.components.v-button :refer [v-link-button]]
   [auto-web.components.v-list   :as wvlist]
   [landing.routes               :as lroutes]))

(defn simulation-body
  [_http-request l]
  (case l
    :en
    [:article.text
     [:h1 "Contact us"]
     [:p
      "We will be able to adapt our explanation, our examples to your industry if we have the opportunity to discuss them with you. Don't hesitate."]
     [:ul.w3-border-0.w3-ul
      [:li (wvlist/v-small-icon (:linkedin lroutes/social)) "  You can contact us on linked-in"]
      [:li
       (wvlist/v-small-icon (:github lroutes/social))
       "  As developpers you can see and interact with us on Github"]
      [:li (wvlist/v-small-icon (:mail lroutes/social)) "  Send an email"]
      [:li
       [:p "Book a meeting: "]
       [:p.w3-center
        (v-link-button (get-in lroutes/social [:meeting :link])
                       "Book a meeting"
                       {:class
                        "w3-btn w3-orange w3-text-white w3-text-bold w3-round w3-ripple"})]]]]
    :fr
    [:article.text
     [:h1 "Contactez-nous"]
     [:p
      "Nous pouvons adaptez nos explications ou nos exemples à votre industrie si nous avons l'opportunité de discutez avec vous. N'hésitez pas."]
     [:ul.w3-border-0.w3-ul
      [:li
       (wvlist/v-small-icon (:linkedin lroutes/social))
       "  Vous pouvez nous contacter sur LinkedIn"]
      [:li
       (wvlist/v-small-icon (:github lroutes/social))
       "  En tant que développeurs, vous pourrez interagir avec nous sur Github"]
      [:li (wvlist/v-small-icon (:mail lroutes/social)) "  nous envoyer un email"]
      [:li
       [:p "ou simplement organiser un rendez-vous:"]
       [:p.w3-center
        (v-link-button (get-in lroutes/social [:meeting :link])
                       "Réservez"
                       {:class
                        "w3-btn w3-orange w3-text-white w3-text-bold w3-round w3-ripple"})]]]]))

(def contacts-map
  {:title {:en "Contacts"
           :fr "Contacts"}
   :description {:en "Simulation"
                 :fr "Two-minute pitch"}
   :handler simulation-body})
