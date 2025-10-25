(ns landing.article.project
  (:require
   [auto-web.components.img  :refer [cimg]]
   [auto-web.components.link :refer [link-opts]]
   [landing.pages.article    :refer [lsection rsection]]))

(def images
  (->> [{:url "/images/architect.png"
         :alt "Architect"
         :img-id :architect}
        {:url "/images/optimisation.png"
         :alt "Optimisation"
         :img-id :optimisation}
        {:url "/images/development.png"
         :alt "Development"
         :img-id :development}]
       (mapv (fn [link] [(:img-id link) link]))
       (into {})))

(def links
  (->> [{:url "https://www.eurodecision.com/algorithmes/intelligence-artificielle"
         :link-id :eurodecision
         :alt "Euro decision"
         :target "blank"}]
       (mapv (fn [link] [(:link-id link) link]))
       (into {})))

(defn project-body
  [_http-request l]
  (case l
    :en
    [(lsection "IT projects" "in supply chain" nil [:p "I offer you 3 types of missions:"])
     [:div.w3-container
      (rsection
       "Architecture"
       "Framing a project"
       (cimg {} :medium (:architect images))
       [:div.w3-border.w3-card.w3-container.w3-right.w3-mobile {:style {:width "40%"
                                                                        :margin-left "3em"}}
        [:h3
         [:i.fa.fas.fa-question.w3-xxxlarge.w3-text-center.w3-circle.w3-light-grey.w3-padding
          {:style {:left "-0.75em"
                   :position "relative"
                   :top "-0.75em"}}]
         " What is architecture?"]
        [:p
         "At the start of a project—and throughout its execution—architecture helps clarify the stakeholders’ objectives, formalize them, and ensure that the project’s development stays aligned with those goals."]]
       [:p
        "I can frame large-scale programs or portfolios of 200+ applications, especially within industrial environments."]
       [:dl
        [:dt.w3-xxlarge "Enterprise Architect"]
        [:dt.w3-xlarge {:style {:margin-inline-start "15px"}}
         "Framing a program"]
        [:dd
         "Defining the scope, objectives, constraints, and delivery approach of a multi-year set of projects before they move into execution. In other words, setting the foundations: what to build, why, with whom, how, and under what conditions."]
        [:dt.w3-xlarge {:style {:margin-inline-start "15px"
                                :margin-top "1em"}}
         "Application portfolio management"]
        [:dd
         "Steering, rationalizing, and evolving the full set of applications within a domain or organization to ensure consistency, performance, compliance, and alignment with business strategy."]
        [:dt.w3-xxlarge {:style {:margin-top "1em"}}
         "Solution Architect"]
        [:dt.w3-xlarge {:style {:margin-inline-start "15px"}}
         "Framing a project"]
        [:dd
         "Defining the scope, objectives, constraints, and delivery methods of a project before launch — setting the foundations for a clear and achievable implementation."]
        [:dt.w3-xlarge {:style {:margin-inline-start "15px"
                                :margin-top "1em"}}
         "Selecting a market software (WMS, TMS, ERP, etc.)"]
        [:dd
         "Defining selection criteria, identifying potential vendors, and conducting evaluations."]]
       [:h2.w3-xxlarge "Project highlights:"]
       [:ul {:style {:margin-top "0"}}
        [:li
         "Framing an artificial intelligence platform for football clubs — aligning business objectives with AI capabilities, defining roadmap and cost, structuring project phases, and assembling the delivery team."]
        [:li
         "Shaping the vision for a PLM system across the Michelin group, covering production, quality, finance, and supply chain — spanning 120 factories and 6 product lines."]
        [:li
         "Leading the selection of an ERP system for one-third of Michelin Europe’s sales operations, supporting automotive manufacturers."]
        [:li "Procurement of a planning tool for Michelin service offerings."]
        [:li
         "Redesigning the order orchestration architecture (Oracle DOO) at Michelin — assessing limitations in existing systems and proposing a new approach."]
        [:li "Selecting and deploying an S&OP platform for the Michelin group."]])]
     (lsection
      "Designing your solution"
      "Advanced development"
      (cimg {} :medium (:development images))
      [:p
       "For highly complex or innovative challenges, where off-the-shelf tools fall short of your expectations, I design and build sophisticated, tailor-made applications:"]
      [:dl
       [:dt.w3-xlarge "Selecting advanced algorithms and defining requirements"]
       [:dd
        "Designing the technical approach and writing the algorithms that bring the solution to life."]
       [:dt.w3-xlarge {:style {:margin-top "1em"}}
        "Development and coding"]
       [:dd "Writing robust, production-grade code and fine-tuning it to your specific context."]]
      [:h2.w3-xxlarge "Project highlights:"]
      [:ul {:style {:margin-top "0"}}
       [:li
        "Developed an online jewelry e-commerce platform in 2004 — at a time when everything had to be built from the ground up."]
       [:li
        "Built a SaaS platform at Storrito — an online tool for scheduling Instagram Reels, featuring on-the-fly video generation, scheduled publishing, and a built-in video editor."]
       [:li "Developed tooling for a digital twin project (ongoing)."]
       [:li
        "Designed an innovative and lightweight solution for precise inventory tracking (Kafka-based stock booster)."]
       [:li
        "Served as process leader for production planning and its implementation in OM Partners systems."]])
     (rsection
      "Optimization and Simulation"
      "Analytical AI"
      (cimg {} :medium (:optimisation images))
      [:p
       "Analytical AI is a branch of artificial intelligence (see "
       [:a (link-opts (:eurodecision links)) "Eurodecision"]
       "), featuring methods particularly well suited for industry and supply chain challenges:"]
      [:div.w3-row-padding
       [:dl.w3-half.w3-padding
        [:dt.w3-xlarge "Simulation"]
        [:dd "Measuring system performance to analyze different scenarios."]]
       [:dl.w3-half.w3-padding {:style {:margin-top "1em"}}
        [:dt.w3-xlarge "Optimization"]
        [:dd "Exploring possible solutions to identify the one that best fits your objectives."]]
       [:dl.w3-half.w3-padding {:style {:margin-top "1em"}}
        [:dt.w3-xlarge "Graph algorithms"]
        [:dd "Finding the shortest path, the maximum flow, and other combinatorial solutions."]]
       [:dl.w3-half.w3-padding {:style {:margin-top "1em"}}
        [:dt.w3-xlarge "Decision support"]
        [:dd
         "Techniques and methods for identifying the best trade-offs — especially when multiple or fuzzy objectives are involved."]]
       [:dl.w3-half.w3-padding {:style {:margin-top "1em"}}
        [:dt.w3-xlarge "Modeling uncertainty"]
        [:dd "Representing and exploring the unknown through probabilistic modeling."]]]
      [:h2.w3-xxlarge {:style {:margin-top "1em"}}
       "Project highlights:"]
      [:ul {:style {:margin-top "0"}}
       [:li
        "Simulation of a flexible workshop — academic research. Modeling and simulating a flexible transport system within a flexible manufacturing environment."]
       [:li
        "Scheduling for a hot-forging workshop — Aubert & Duval. Short-term scheduling of forging operations to maximize press utilization, while respecting reheating constraints (minimum and maximum times between operations)."]
       [:li
        "Make-or-buy analysis for textile fibers — Michelin. Using procurement contracts while respecting minimum and maximum purchase thresholds, to maximize profit while meeting demand."]
       [:li
        "Optimization of the distribution network — Michelin. Design and deployment of logistics distribution networks. Developed in SQL, and running in production at Michelin for over 15 years."]
       [:li
        "Simulation of a tire factory — Michelin. Development and deployment of a tool for automatically generating realistic mid-term production schedules."]])
     (lsection
      "General skills"
      nil
      nil
      [:p
       "To successfully deliver all these projects, I have developed and refined a broad set of skills: project management (both Agile and Waterfall), team leadership, building and validating projects with executives, writing specifications, and performing testing."])]
    :fr
    [(lsection "Projets informatiques"
               "en chaîne logistique"
               nil
               [:p "Je vous offre 3 types de mission :"])
     [:div.w3-container
      (rsection
       "L'architecture"
       "Cadrer un projet"
       (cimg {} :medium (:architect images))
       [:div.w3-border.w3-card.w3-container.w3-right.w3-mobile {:style {:width "40%"
                                                                        :margin-left "3em"}}
        [:h3
         [:i.fa.fas.fa-question.w3-xxxlarge.w3-text-center.w3-circle.w3-light-grey.w3-padding
          {:style {:left "-0.75em"
                   :position "relative"
                   :top "-0.75em"}}]
         " L'architecture c'est quoi"]
        [:p
         "Au démarrage d'un projet, puis tout au long de celui-ci, l'architecture fait émerger les objectifs des décideurs, les formalise et s'assure que le développement du projet suit ces orientations."]]
       [:p
        "Je peux cadrer des programmes, portefeuille de 200+ applications, en particulier dans un périmètre industriel."]
       [:dl
        [:dt.w3-xxlarge "Architecte urbaniste"]
        [:dt.w3-xlarge {:style {:margin-inline-start "15px"}}
         "Cadrer un programme"]
        [:dd
         "C’est définir le périmètre, les objectifs, les contraintes et les modalités de réalisation d’un ensemble de projet, sur des années avant son lancement opérationnel. Autrement dit, c’est poser les bases du projet : ce qu’on veut faire, pourquoi, avec qui, comment et à quelles conditions."]
        [:dt.w3-xlarge {:style {:margin-inline-start "15px"
                                :margin-top "1em"}}
         "Gestion de portefeuille d'applications"]
        [:dd
         "C’est piloter, rationaliser et faire évoluer l’ensemble des applications informatiques d’un domaine ou d’une organisation, afin d’assurer leur cohérence, performance, conformité et alignement avec la stratégie d’entreprise."]
        [:dt.w3-xxlarge {:style {:margin-top "1em"}}
         "Architecte solution"]
        [:dt.w3-xlarge {:style {:margin-inline-start "15px"}}
         "Cadrer un projet"]
        [:dd
         "C’est définir le périmètre, les objectifs, les contraintes et les modalités de réalisation d’un projet avant son lancement opérationnel. Autrement dit, c’est poser les bases du projet : ce qu’on veut faire, pourquoi, avec qui, comment et à quelles conditions."]
        [:dt.w3-xlarge {:style {:margin-inline-start "15px"
                                :margin-top "1em"}}
         "Choix d'un logiciel du marché (WMS, TMS, ERP, ...)"]
        [:dd
         "Définition des critères de choix, proposition de candidats, évaluation des candidats"]]
       [:h2.w3-xxlarge "Exemples de réalisations :"]
       [:ul {:style {:margin-top "0"}}
        [:li
         "Cadrage d'une plateforme d'intelligence artificielle pour des clubs de football. Aligner les besoins business et les capacités des IAs. Ecrire le planning, chiffrer le projet, cadrer chaque lot, monter l'équipe."]
        [:li
         "Créer la vision d’un PLM dans le groupe Michelin incluant les métiers de la production, qualité, finance et Supply Chain… pour 120 usines, 6 lignes produits."]
        [:li
         "Acheter un ERP pour un tiers des ventes Michelin Europe - pour servir les constructeurs automobiles."]
        [:li "Acheter un outil de planification pour les offres de service Michelin."]
        [:li
         "Réécrire l'architecture de l'orchestration des commandes (DOO Oracle) de Michelin : évaluer les difficultés des architectures existantes et faire une contre proposition."]
        [:li "Acheter et déployer un outil S&OP pour le groupe Michelin."]])]
     (lsection
      "La conception de votre outil"
      "Développement avancé"
      (cimg {} :medium (:development images))
      [:p
       "Sur des sujets particulièrement complexes ou novateur, là où les outils du commerce ne correspondent pas à votre niveau d'exigence, je sais développer des applications complexes :"]
      [:dl
       [:dt.w3-xlarge "Choisir les algorithmes avancés, faire un cahier des charges"]
       [:dd "Ecrire les algorithmes, concevoir la solution"]
       [:dt.w3-xlarge {:style {:margin-top "1em"}}
        "Développer, coder"]
       [:dd "Ecrire concrètement le code, les régler sur votre cas particulier."]]
      [:h2.w3-xxlarge "Exemples de réalisations :"]
      [:ul {:style {:margin-top "0"}}
       [:li
        "Développement d'un site web de ventes en ligne de bijouterie, en 2004, quand il fallait tout construire."]
       [:li
        "Développement d'un SAAS chez Storrito - un outil en ligne pour planifier des reels sur Instagram. Création de vidéos à la volée, publication planifiée, éditeur de vidéos."]
       [:li "Développement d'un outillage pour le jumeau numérique (en cours)"]
       [:li
        "Conception d'une solution innovante de suivi de stock précis et simple (kafka / booster stock)"]
       [:li
        "Process leader pour la planification de production et son implémentation dans les outils d’OM Partners."]])
     (rsection
      "Optimisation et simulation"
      "IA analytique"
      (cimg {} :medium (:optimisation images))
      [:p
       "L'IA analytique est une branche de l'IA (cf. "
       [:a (link-opts (:eurodecision links)) "Eurodécision"]
       "), elle contient des méthodes particulièrement adaptées à l'industrie et à la Supply Chain :"]
      [:div.w3-row-padding
       [:dl.w3-half.w3-padding
        [:dt.w3-xlarge "Simulation"]
        [:dd "Mesurer les performances d'un système pour analyser un scénario."]]
       [:dl.w3-half.w3-padding {:style {:margin-top "1em"}}
        [:dt.w3-xlarge "Optimisation"]
        [:dd "Explorer les solutions possibles pour trouver celle qui vous correspond."]]
       [:dl.w3-half.w3-padding {:style {:margin-top "1em"}}
        [:dt.w3-xlarge "Algorithmes de graphes"]
        [:dd "Trouver un chemin le plus court, un flot le plus grand..."]]
       [:dl.w3-half.w3-padding {:style {:margin-top "1em"}}
        [:dt.w3-xlarge "Aide à la décision"]
        [:dd
         "Technique et méthode pour chercher la meilleure solution, avec plusieurs objectifs, ou des objectifs flous."]]
       [:dl.w3-half.w3-padding {:style {:margin-top "1em"}}
        [:dt.w3-xlarge "Modélisation du hasard"]
        [:dd "Modéliser et explorer la part d'inconnu"]]]
      [:h2.w3-xxlarge {:style {:margin-top "1em"}}
       "Exemples de réalisations :"]
      [:ul {:style {:margin-top "0"}}
       [:li
        "Simulation d'un atelier flexible - Recherche universitaire. Simulation d'un moyen de transport flexible dans un atelier flexible."]
       [:li
        "Ordonnancement d'un atelier de forge à chaud - Aubert & Duval. Planifier le court terme d'un atelier de forge à chaud, l'occupation maximum de la presse est l'objectif. Entre deux forgeages, la pièce doit retourner en chauffe, avec un temps minimum et maximum de chauffe."]
       [:li
        "Make or buy des fibres textiles - Michelin. Utiliser les contrats d'achats de textile, en respectant les minimums et maximums contractuels. Maximiser le profit tout en respectant la demande."]
       [:li
        "Optimisation du réseau de distribution - Michelin. Conception et déploiement de réseaux de distribution logistique. Développement en SQL, en production chez Michelin depuis 15 ans."]
       [:li
        "Une simulation d'une usine de pneumatiques - Michelin. Développer et déployer un outil de création automatique de planning moyen terme réaliste."]])
     (lsection
      "Compétences générales"
      nil
      nil
      [:p
       "Pour mener à bien tous ces projets, j'ai appris et aiguisé des compétences générales : la gestion de projet (agile et cycle en V), la gestion d'équipe, construire et faire valider des projets aux dirigeants, écriture de spécification, faire les tests..."])]))

(def project-map
  {:title {:en "Your IT and Supply Chain projects"
           :fr "Vos projets informatique et Supply chain"}
   :description {:en "Your IT and Supply Chain projects"
                 :fr "Vos projets informatique et Supply chain"}
   :handler project-body})
