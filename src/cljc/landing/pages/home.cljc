(ns landing.pages.home
  "Specific to the website home page"
  (:require
   [auto-web.assembled.people-card :refer [cpeople-card-with-modal]]
   [auto-web.components.button     :refer [clink-button]]
   [auto-web.components.img        :refer [cimg]]
   [auto-web.components.link       :refer [clink]]
   [auto-web.components.menu       :refer [chorizontal-text-menu]]
   [landing.article.who-are-we     :as who-are-we]
   [landing.language               :refer [landing-lang-bar]]
   [landing.pages.structure        :refer [cfooter]]
   [landing.routes                 :as lroutes]))

(def images
  (->> [{:url "/images/factory.jpg"
         :alt "factory illustration"
         :img-id :factory}]
       (mapv (fn [link] [(:img-id link) link]))
       (into {})))

(def dic
  {:title {:fr "Anticipez, optimisez, expliquez, comprenez votre PMI"
           :en "Reduce costs, optimize stock, improve the service level"}
   :sub-title {:fr "Le jumeau numérique d'Hephaistox"
               :en "Hephaistox digital twin"}
   :home-page {:en "Hephaistox home page"
               :fr "Page d'accueil Hephaistox"}
   :desc
   {:fr
    "Nous commençons par des consultations approfondies pour identifier les questions clés, nous créons le jumeau numérique de votre usine et nous effectuons des simulations pour transformer l'intuition en réalité et garantir un processus de mise en œuvre efficace et sans heurts."
    :en
    "We start with in-depth consultations to identify the key question, we craft the digital twin of your plant and run simulations to turn intuition into actuals and guarantee a smooth and efficient implementation process."}
   :but-first {:fr "Mais d'abord ...."
               :en "But first ..."}
   :speak-about-it {:fr "Parlons-en"
                    :en "Let's talk"}
   :disclaimer {:fr "Disclaimer"
                :en "Disclaimer"}
   :privacy {:fr "Privacy"
             :en "Privacy"}
   :about-us {:fr "A propos de nous"
              :en "About us"}
   :co-founder {:fr "Co-fondateur d'Hephaistox"
                :en "Co-founder of Hephaistox"}
   :read-more {:en "Read more"
               :fr "Voir plus"}
   :about-us-desc
   {:en
    "Hi, we are Mati and Anthony, co-founders of Hephaistox! Together we unite our passion for solving problems in the supply chain field with the help of software development. Our goal is to provide high-level solutions to small and medium industries, as we firmly believe that the strength and self-sufficiency of Europe lie within our local industries. We are committed to offering them the care and solutions that are often only available to large corporations."
    :fr
    "Bonjour, nous sommes mati et anthony, co-fondateurs d’Hephaistox ! Ensemble, nous avons unis notre passion pour la résolution de problèmes dans le domaine de la chaîne logistique à l’aide de logiciels informatiques. Notre but est de proposer des solutions de haute qualité pour les entreprises et industries de petites et moyennes tailles. Nous avons la conviction profonde que l’efficacité et l’auto suffisance de l’Europe passera par nos industries locales et leur dynamisme. Nous nous engageons à offrir l’attention et les solutions souvent réservées aux grandes corporations."}
   :simulation-offer
   {:fr
    [:div
     [:h1 "Offre de simulation"]
     [:p "Notre offre de simulation supprimera la part de supposition de votre prise de décision."]
     [:p
      "De manière générale, la simulation peut être utilisée pour répondre aux questions de type \"Que se passe-t-il si? \" de votre usine."]
     [:ul
      [:li "Que se passe-t-il si la cadence de votre usine augmente?"]
      [:li "Que se passe-t-il si l'en-cours est augmenté?"]
      [:li "Que se passe-t-il si le mix produit de la demande client évolue?"]
      [:li "Que se passe-t-il si ce nouveau produit est industrialisé dans le même atelier?"]
      [:li "Que se passe-t-il si une nouvelle machine est ajoutée dans l'atelier?"]]]
    :en
    [:div
     [:h1 "Simulation offer"]
     [:p "Our simulation offer will take the guesswork out of your decision making."]
     [:p
      "Generally speaking, simulation can be used to answer questions concerning your plant starting with “What if?”"]
     [:ul
      [:li "What if the throughput is increased?"]
      [:li "What if more work in progress is authorized?"]
      [:li "What if the mix of products of customers is evolving?"]
      [:li "What if this new product is industrialized in the same workshop?"]
      [:li "What if we add a new machine to the workshop?"]]]}})

(def ^:private in-menu-items
  (vals (select-keys lroutes/links [:digital-twin :projets :who-are-we])))

(def full-dic (merge dic lroutes/dic who-are-we/dic))

(defn body
  [http-request]
  (let [l (get http-request :lang)
        tr #(get-in full-dic [% l])
        factory (:factory images)]
    [:body.w3-row.w3-xlarge {}
     [:div#app {:style {:min-height "87em"}}
      [:div#main-header
       (chorizontal-text-menu {:class "w3-small w3-card"}
                              (mapv #(update % :text tr) in-menu-items))]
      [:div#first-section.w3-row
       [:div#small-header.w3-panel.w3-row.l6.w3-col
        (vec (concat
              [:header.w3-display-container.w3-container
               [:div.w3-display-left
                (clink {} (:home lroutes/links) (cimg {} :tiny (:hephaistox-logo lroutes/images)))]]
              [[:div.w3-right (landing-lang-bar {} http-request)]]))
        [:div.w3-padding.w3-auto {:style {:max-width "40em"}}
         [:br]
         [:h2.w3-right-align.w3-bold.text.w3-text-red.w3-xxxlarge (tr :sub-title)]
         [:h1.w3-bold.text.w3-xxxlarge (tr :title)]
         [:br]
         [:div.w3-container
          [:p.text (tr :desc)]
          [:p.text (tr :but-first)]
          [:br]
          [:div.w3-display-container.w3-panel
           (clink-button
            {:class
             "w3-display-middle w3-btn w3-orange w3-text-white w3-text-bold w3-round w3-ripple"}
            (tr :speak-about-it)
            (get-in lroutes/social [:meeting :link]))]
          [:br]]]]
       [:div#styled-factory.w3-display-container.l6.w3-hide-small.w3-hide-medium {:style {:height
                                                                                          "40em"
                                                                                          :overflow
                                                                                          "hidden"}}
        [:div {:style {:height "100%"}}
         [:img {:style {:height "100%"}
                :alt (:alt factory)
                :src (:url factory)}]
         [:svg.w3-transparent.w3-hide-small.w3-hide-medium.w3-display-left.fill-bg-color
          {:style {:height "100%"}
           :viewbox "0 0 100 100"}
          [:polygon {:points "0,100 0,0 0,0 10,100"}]]]]]
      [:div#offers-section.w3-row.w3-flat-midnight-blue.w3-padding
       [:div.w3-content.text.w3-padding {:style {:max-width "40em"}}
        (tr :simulation-offer)
        (clink-button {:class "w3-text-orange w3-text-bold w3-round"}
                      (tr :read-more)
                      (:digital-twin lroutes/links))]]
      [:div#about-us-section.w3-row
       [:div.w3-content.text.w3-padding {:style {:max-width "40em"}}
        [:h1 (tr :about-us)]
        [:p (tr :about-us-desc)]]]
      [:div.w3-row.w3-panel
       [:div.w3-col.m1 [:p]]
       (cpeople-card-with-modal {}
                                :anthony-card
                                [{:fa-icon "fa-linkedin fa-brands"
                                  :link (:linkedin-anthony lroutes/links)}]
                                (:anthony who-are-we/images)
                                "Anthony CAUMOND"
                                (tr :co-founder)
                                (tr :about-anthony))
       [:div.w3-col.m2 [:p]]
       (cpeople-card-with-modal {}
                                :mati-card
                                [{:fa-icon "fa-linkedin fa-brands"
                                  :link (:linkedin-mati lroutes/links)}]
                                (:mati who-are-we/images)
                                "Mati MAZURCZAK"
                                (tr :co-founder)
                                (tr :about-mati))
       [:div.w3-col.m1 [:p]]]]
     (cfooter {} http-request)]))
