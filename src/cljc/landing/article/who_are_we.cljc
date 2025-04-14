(ns landing.article.who-are-we
  (:require
   [auto-web.components.img  :refer [cicon cimg]]
   [auto-web.components.link :refer [clink cspan-link]]
   [landing.routes           :as lroutes]))

(def images
  (->> [{:url "/img/anthony.jpeg"
         :alt "Portrait anthony"
         :img-id :anthony}
        {:url "/img/mati.jpeg"
         :alt "Portrait mati"
         :img-id :mati}
        {:url "/img/resume.jpg"
         :alt "Resume"
         :img-id :resume}]
       (mapv (fn [link] [(:img-id link) link]))
       (into {})))

(def links
  (->> [{:link-id :resume
         :url "/cv_caumond.pdf"}]
       (mapv (fn [link] [(:link-id link) link]))
       (into {})))

(def dic
  {:about-anthony
   {:en
    [:div
     [:p "I am Anthony, by nature I am an expert."]
     [:p
      "After "
      [:b "developing"]
      " games, demos and professional software in my younger years, I naturally selected an IT engineering school for my initial training and then a doctorate in computer science."]
     [:p
      "I have spent 6 years of "
      [:b "research"]
      " on operational research issues in industrial projects (PSA, Michelin, Aubert & Duval, ...). These practical topics have been supplemented by two high-level scientific journals: (European Journal of Operational Research) and COR (Computer & Operations Research)."]
     [:p
      "My research position included a "
      [:b "teaching"]
      " component , over 500 hours, because teaching is one of the best ways to understand a subject. I taught operations research, mathematics, software development, and supply chain. I have continued to teach regularly since then."]
     [:p
      "I have two decades of industrial experience in project management with an IT and "
      [:b "supply chain"]
      " component and software development, my main experience was at Michelin, where I worked extensively in a wide variety of industries (synthetic rubber, fabric, fibers, tires ...)."]
     [:div.w3-center (clink {} (:resume links) (cimg {} :small (:resume images)))]]
    :fr
    [:div
     [:p "Je suis Anthony, par nature, je suis un expert."]
     [:p
      "Après avoir fait du "
      [:b "développement"]
      " dans mes jeunes années, des jeux, démos et logiciels professionnels, j'ai naturellement suivi une formation dans une école d'ingénieurs en informatique puis un doctorat en informatique."]
     [:p
      "J'ai effectué mes 6 années de "
      [:b "recherche"]
      " sur des problèmes de recherche opérationnelle sur des projets industriels (PSA, Michelin, Aubert & Duval, ...). Ces sujets pratiques ont été complété de deux revues scientifiques de haut niveau: (European Journal of Operational Research) and COR (Computer & Operations Research)."]
     [:p
      "Mon poste de recherche contenait une partie "
      [:b "d'enseignement"]
      ", plus de 500 heures, car enseigner est un des meilleurs moyens de comprendre un sujet. J’ai enseigné de la recherche opérationnelle, des mathématiques, du développement informatique et de la chaîne logistique. Je continue régulièrement des enseignements depuis."]
     [:p
      "J'ai deux décennies d’expérience industrielle dans la gestion des projets avec une composante informatique et "
      [:b "chaîne logistique"]
      " et de développement logiciel, mon expérience principale a été chez Michelin, où j'ai travaillé en profondeur à une grande diversité d’industries (gomme synthétique, tissu, fibres, pneus ...)."]
     [:div.w3-center (clink {} (:resume links) (cimg {} :small (:resume images)))]]}
   :about-mati
   {:en
    [:p
     "I’m Mati, with a decade of experience in software development. I’ve thrived in fast-paced startups that revolve around cutting-edge technologies. My role was always being a problem solver, doesn’t matter if it was code, people relations, or business goals. And for project success, all of them need to be well taken care of. I’ve worked with all sorts of software sizes from e-commerce shops, through developing advanced technologies for movement rehabilitation to AI - NLP platforms for the biggest financial institutions and telecom companies. I was always interested in the full scope of the work, managing teams, creating software, and in the end solving problems for the end users that were the most valuable for the company. And always participated in the whole product cycle, from idea through creation to supporting it. I love software, but even more, I love creating software that is solving the most painful problems. Technologies are only tools, and I prefer tools that let me see and focus on the data, real problems, and information. And which needs to be understood deeply also on the aspect of your team's needs and usage to be fully successful. I also put high pressure on the highest code quality, documentation, and testing to ensure that the software is working as designed and can be easily modified and supported by other teams."]
    :fr
    [:div
     [:p "Et je suis Mati, avec une décennie d’expérience en développement logiciel."]
     [:p
      "J’ai évolué dans des startups à croissance rapide, autour des nouvelles technologies. Je me suis positionné comme quelqu’un qui résout des problèmes, que ce soit du code, des relations humaines ou des objectifs business. Et pour que ce projet réussisse, nous avons besoin des trois."]
     [:p
      "J’ai travaillé avec toute sorte de taille de logiciel, de magasins en lignes, à des logiciels pour la réhabilitation de mouvements jusqu’à de l’intelligence artificielle (NLP - Natural Language Processing) pour les grandes entreprises financières et téléphoniques. J’ai toujours été intéressé par le travail sur l’ensemble du périmètre, gérer les équipes, créer les logiciels, et pour finir la résolution des problèmes des utilisateurs finaux, utilisateurs qui ont le plus de valeurs pour l’entreprise. Et j’ai toujours participé dans l’ensemble du cycle produit, depuis l’idée jusqu’à sa création. J’adore le logiciel, mais plus encore, j’adore créer du logiciel qui résout de vrais problèmes."]
     [:p
      "Les technologies ne sont que des outils, et je préfère les technologies qui me permettent de voir et de me focaliser sur les données, les réels problèmes et les informations. Tout ce qui a besoin d’être compris en détail pour le succès de votre équipe."]
     [:p
      "Je mets aussi beaucoup de pression sur la qualité du code, la documentation, les tests pour s’assurer que le logiciel fourni fonctionne tel qu’attendu et puisse être facilement modifié et supporté par d’autres équipes."]]}
   :who-are-we {:fr "Qui sommes-nous?"
                :en "Who are we?"}
   :craftsman
   {:fr
    "Nous sommes des artisans, fiers de notre art (l’informatique et la chaîne logistique). Nous avons passé des années à perfectionner notre art, à comprendre les gens, leur problème de chaînes logistiques, les résoudre (en incluant mais non limité à du logiciel)."
    :en
    "We are craftsmen, proud of our craft (IT and supply chain). We have spent years perfecting our craft, understanding people, their supply chain problems, and solving them (including but not limited to software)."}
   :uniqueness
   {:fr
    "En travaillant comme consultant pendant deux décennies, nous avons remarqué que chaque entreprise est unique, et que les problèmes que les entreprises rencontrent - même s’ils sont similaires - nécessitent chacune une approche différente (à l’image des personnes qui les gèrent)."
    :en
    "Working as a consultant for two decades, we've noticed that every business is unique, and the problems they face—even if they're similar—each require a different approach (just like the people who run them)."}})

(defn who-are-we-body
  [_http-request l]
  (let [tr #(get-in dic [% l])]
    [:article.text
     [:h1 "Qui sommes-nous?"]
     [:p (tr :craftsman)]
     [:p (tr :uniqueness)]
     [:h2
      "Anthony CAUMOND "
      (cspan-link {} (:linkedin-anthony lroutes/links) (cicon {} "fa-linkedin fa-brands"))]
     [:div.w3-center (cimg {} :medium (:anthony images))]
     (tr :about-anthony)
     [:h2
      "Mati MAZURCZAK "
      (cspan-link {} (:linkedin-mati lroutes/links) (cicon {} "fa-linkedin fa-brands"))]
     [:div.w3-center (cimg {} :medium (:mati images))]
     (tr :about-mati)]))

(def who-are-we-map
  {:title {:en "Who are we?"
           :fr "Qui sommes-nous?"}
   :description {:en "Who are we?"
                 :fr "Qui sommes-nous?"}
   :handler who-are-we-body})
