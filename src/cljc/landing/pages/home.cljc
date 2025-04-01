(ns landing.pages.home
  "Specific to the website home page"
  (:require
   [auto-web.assembled.v-people-card :refer [people-card-with-description]]
   [auto-web.components.v-button     :refer [v-link-button]]
   [auto-web.components.v-img        :refer [v-img]
                                     :as    wvimage]
   [auto-web.components.v-link       :refer [v-a]]
   [landing.language                 :refer [lang-bar]]
   [landing.pages.structure          :refer [footer]]
   [landing.routes                   :as lroutes]))

(def images
  (->> [{:url "/img/factory.jpg"
         :alt "factory illustration"
         :name :factory}
        {:url "/img/anthony.jpeg"
         :alt "Portrait anthony"
         :name :anthony}
        {:url "/img/mati.jpeg"
         :alt "Portrait mati"
         :name :mati}]
       (mapv (fn [link] [(:name link) link]))
       (into {})))

(def dic
  {:title {:fr "Anticipez, optimisez, expliquez, comprenez votre PMI"
           :en "Reduce costs, optimize stock, improve the service level"}
   :sub-title {:fr "Notre jumeau numérique"
               :en "Our digital twin"}
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
   :b {:fr "Une compréhension profonde de la théorie, de l'enseignement dans le supérieur"
       :en "Deep understanding of theory and years of teaching at university"}
   :a {:fr "20+ années d'expérience industrielle en chaîne logistique et en informatique"
       :en "20+ years of commercial experience with supply chain and IT"}
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
     [:h1 "Offre de simulation"]
     [:p "Our simulation offer will take the guesswork out of your decision making."]
     [:p
      "Generally speaking, simulation can be used to answer questions concerning your plant starting with “What if?”"]
     [:ul
      [:li "What if the throughput is increased?"]
      [:li "What if more work in progress is authorized?"]
      [:li "What if the mix of products of customers is evolving?"]
      [:li "What if this new product is industrialized in the same workshop?"]
      [:li "What if we add a new machine to the workshop?"]]]}
   :about-anthony
   {:en
    [:p
     "I'm Anthony, and I have a solid foundation from a knowledge perspective, but also some practical experiences. My educational background includes an engineering school education, a Master of Science degree, and a Ph.D. in computer science. Additionally, I hold professional certifications in Architecture (TOGAF) and Supply Chain (APICS). Practically speaking, it starts with my Ph.D. research that involved collaborating with six different industries and was focused on scheduling problems and supplementary constraints, utilizing highly successful techniques. These findings can prove extremely useful in your industries. Teaching has also been a significant part of my journey as I believe that the best way to truly understand something is by teaching it. I have dedicated six full years to imparting knowledge, where I taught operations research, mathematics, development, and supply chain. I take pride in having my work featured in prestigious scientific journals such as EJOR (European Journal of Operational Research) and COR (Computer & Operations Research), both specializing in operations research. With over two decades of commercial experience in supply chain management and software development, my most significant experience lies with Michelin, which offers a diverse range of industries and positions. The supply chain is not just my passion, it's my life's devotion. Even my thesis, specifically Chapter 5 laid the foundation for the software solutions we can now develop for you. It's important to note that Chapter 5 was an initial proposal, and with advancements in technology, maturity, and practical experience that I’ve gained over the years, I am now well-equipped to deliver exceptional results. Rest assured, this endeavor is not impromptu, but rather the culmination of a long path, and I hope you'll take advantage of the low-hanging fruits now available."]
    :fr
    [:p
     "Je suis Anthony et j'ai des connaissances solides. J'ai suivi une formation dans une école d'ingénieurs, j'ai obtenu une maîtrise en sciences et un doctorat en informatique. En outre, je suis titulaire de certifications professionnelles en architecture (TOGAF) et en chaîne d'approvisionnement (APICS). D’un point de vue pratique, j’ai commencé pendant mon doctorat qui m’a permis d’apporter des connaissances de recherches opérationnelles à six industries différentes. J’étais concentré sur les problèmes d’ordonnancement avec contraintes supplémentaires, en utilisant des techniques tout à fait fructueux. Nous croyons que ces techniques sont très utiles pour vos industries. L’enseignement a aussi été une partie significative de cette aventure, car enseigner est un des meilleurs moyens de comprendre un sujet. J’ai dédié six années pleines à enseigner la recherche opérationnelle, des mathématiques, le développement informatique, la chaîne logistique. Je suis fier d’avoir écrit deux revues scientifiques de haut niveau: (European Journal of Operational Research) and COR (Computer & Operations Research) tous les deux spécialisés dans les problèmes de recherche opérationnelle. Avec mes deux décennies d’expérience industrielle dans la gestion de la chaîne logistique et de développement logiciel, mon expérience principale a été chez Michelin, qui m’a offert de m’intéresser à une grande diversité d’industries. La chaîne logistique n’est pas seulement une passion, mais une vraie dévotion. Même en thèse, j’ai consacré un chapitre 5 entier à la création d’un logiciel de gestion de chaîne logistique. Ce n’était qu’une proposition initiale qui, avec l’avancement des technologies et la connaissance pratique que j’ai acquise avec les années, je me sens maintenant tout à fait bien équiper pour vous délivrer des résultats exceptionnels. Soyez assuré que ces objectifs ne sont pas improvisés, mais plutôt l’aboutissement d’un long chemin, dont j’espère que vous serez les bénéficiaires, vous qui me lisez."]}
   :about-mati
   {:en
    [:p
     "I’m Mati, with a decade of experience in software development. I’ve thrived in fast-paced startups that revolve around cutting-edge technologies. My role was always being a problem solver, doesn’t matter if it was code, people relations, or business goals. And for project success, all of them need to be well taken care of. I’ve worked with all sorts of software sizes from e-commerce shops, through developing advanced technologies for movement rehabilitation to AI - NLP platforms for the biggest financial institutions and telecom companies. I was always interested in the full scope of the work, managing teams, creating software, and in the end solving problems for the end users that were the most valuable for the company. And always participated in the whole product cycle, from idea through creation to supporting it. I love software, but even more, I love creating software that is solving the most painful problems. Technologies are only tools, and I prefer tools that let me see and focus on the data, real problems, and information. And which needs to be understood deeply also on the aspect of your team's needs and usage to be fully successful. I also put high pressure on the highest code quality, documentation, and testing to ensure that the software is working as designed and can be easily modified and supported by other teams."]
    :fr
    [:p
     "Et je suis Mati, avec une décennie d’expérience en développement logiciel. J’ai évolué dans des startups à croissance rapide, autour des nouvelles technologies. Je me suis positionné comme quelqu’un qui résout des problèmes, que ce soit du code, des relations humaines ou des objectifs business. Et pour que ce projet réussisse, nous avons besoin des trois. J’ai travaillé avec toute sorte de taille de logiciel, de magasins en lignes, à des logiciels pour la réhabilitation de mouvements jusqu’à de l’intelligence artificielle (NLP - Natural Language Processing) pour les grandes entreprises financières et téléphoniques. J’ai toujours été intéressé par le travail sur l’ensemble du périmètre, gérer les équipes, créer les logiciels, et pour finir la résolution des problèmes des utilisateurs finaux, utilisateurs qui ont le plus de valeurs pour l’entreprise. Et j’ai toujours participé dans l’ensemble du cycle produit, depuis l’idée jusqu’à sa création. J’adore le logiciel, mais plus encore, j’adore créer du logiciel qui résout de vrais problèmes. Les technologies ne sont que des outils, et je préfère les technologies qui me permettent de voir et de me focaliser sur les données, les réels problèmes et les informations. Tout ce qui a besoin d’être compris en détail pour le succès de votre équipe. Je mets aussi beaucoup de pression sur la qualité du code, la documentation, les tests pour s’assurer que le logiciel fourni fonctionne tel qu’attendu et puisse être facilement modifié et supporté par d’autres équipes."]}})

(defn body
  [http-request]
  (let [l (get http-request :lang)
        tr #(get-in dic [% l])]
    [:body.w3-row.w3-xlarge {}
     [:div {:style {:min-height "87em"}}
      [:div#first-section.w3-row
       [:div#small-header.w3-panel.w3-row.l6.w3-col
        (vec (concat [:header.w3-display-container.w3-container
                      [:div.w3-display-left
                       (v-a (:home lroutes/links) (v-img (:hephaistox-logo lroutes/images) :tiny))]]
                     [[:div.w3-right (lang-bar http-request)]]))
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
           (v-link-button
            (get-in lroutes/social [:meeting :link])
            (tr :speak-about-it)
            {:class
             "w3-display-middle w3-btn w3-orange w3-text-white w3-text-bold w3-round w3-ripple"})]
          [:br]]]]
       [:div#styled-factory.w3-display-container.w3-col.l6.w3-hide-small.w3-hide-medium
        {:style "height:40em;overflow: hidden;"}
        [:svg.w3-display-left.w3-transparent.w3-hide-small.w3-hide-medium {:style {:height "40em"
                                                                                   :fill "white"}
                                                                           :viewbox "0 0 100 100"}
         [:polygon {:points "0,100 0,0 0,0 10,100"
                    :style {:height "100%"
                            :z-index 100}}]]
        [:img {:style {:z-index 20
                       :class "w3-display-right"
                       :height "100%"}
               :alt (:alt (:factory images))
               :src (:url (:factory images))}]]]
      [:div#offers-section.w3-row.w3-flat-midnight-blue.w3-padding
       [:div.w3-content.text.w3-padding {:style {:max-width "40em"}}
        (tr :simulation-offer)
        (v-link-button (:simulation lroutes/links)
                       (tr :read-more)
                       {:class " w3-text-orange w3-text-bold w3-round"})]]
      [:div#about-us-section.w3-row
       [:div.w3-content.text.w3-padding {:style {:max-width "40em"}}
        [:h1 (tr :about-us)]
        [:p (tr :about-us-desc)]]]
      [:div.w3-row
       [:div.w3-col.m1 [:p]]
       (people-card-with-description :anthony-card
                                     [{:fa-icon "fa-linkedin fa-brands"
                                       :link (:linkedin-anthony lroutes/links)}]
                                     (:anthony images)
                                     "Anthony CAUMOND"
                                     (tr :co-founder)
                                     (tr :about-anthony))
       [:div.w3-col.m2 [:p]]
       (people-card-with-description :mati-card
                                     [{:fa-icon "fa-linkedin fa-brands"
                                       :link (:linkedin-mati lroutes/links)}]
                                     (:mati images)
                                     "Mati MAZURCZAK"
                                     (tr :co-founder)
                                     (tr :about-mati))
       [:div.w3-col.m1 [:p]]]
      [:br]
      [:br]]
     (footer http-request)]))
