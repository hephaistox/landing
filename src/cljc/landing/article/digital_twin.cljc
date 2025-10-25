(ns landing.article.digital-twin
  (:require
   [auto-web.components.img :refer [cimg]]))

(def images
  (->> [{:url "/images/travaux.png"
         :alt "En travaux"
         :img-id :travaux}]
       (mapv (fn [link] [(:img-id link) link]))
       (into {})))

(defn digital-twin-body
  [_http-request l]
  (case l
    :en
    [[:h1 "Digital twin offer"]
     [:p "Hephaistox is building a digital twin solution for small industries."]
     [:p
      "This solution, tailored for SMEs, will enable simulations and scenario evaluations to be carried out quickly, at a cost appropriate for SMEs."]
     (cimg {:class "w3-image w3-auto"} :medium (:travaux images))
     (comment
       [:p "Welcome to Hephaistox, the supply chain craftsman!"]
       [:p
        "Are you an industrial? Do you have some difficult questions to answer? So think about working with Hephaistox to help you if:"]
       [:ul
        [:li "Do you have some doubts about the profitability of an investment?"]
        [:li
         "Do you search for a different organization to decrease your energy consumption, but you have doubts about the relevance of your solutions?"]
        [:li "Do you search for alternatives to decrease the scrap?"]
        [:li
         "Do you think you can decrease your stock level but you don’t know the detailed conditions?"]
        [:li "And some other questions you may have…"]
        [:li
         "While your experts and their expertise are invaluable, there are instances when the questions are too complex, the impacts extend beyond multiple domains, or the situation has never been encountered before. In such scenarios, we step in to assist both your experts and the decision-maker."]]
       [:p
        "By partnering with Hephaistox, you're not just getting consultants – you're gaining supply chain craftsmen dedicated to solving your unique challenges with custom-made solutions. The methodology we use is proven by our years of experience, it consists of the following main steps:"]
       [:ul
        [:li
         [:b "Define the question: "]
         "With our Supply Chain knowledge and practical experience in simulation, we can help you to ask the right question. “A problem well-defined is a problem half-solved”"]
        [:li
         [:b "Guide the modeling of your plant: "]
         "Our skills in modeling will help you to start with more important constraints."]
        [:li [:b "Solving the problem: "] "We come up with methods and toolings."]
        [:li
         [:b "Make the decision: "]
         "To create a convergence of all decision makers (whatever the domain is, from operators to managers)"]]
       [:p
        "That approach - using the scientific method - will give you confidence in your decision-making, and give precise decisions."])]
    :fr
    [[:h1 "Offre de jumeau numérique"]
     [:p "Hephaistox construit une offre de jumeau digital pour les petites industries."]
     [:p
      "Cette offre, dimensionée pour les PMIs (Petites et Moyennes Industries), permettra de réaliser des simulations, évaluer des scénarios rapidement, pour un coût adapté aux PMIs."]
     (cimg {:class "w3-image w3-auto"} :medium (:travaux images))
     (comment
       [:p
        "Vous êtes industriels? Vous avez une décision difficile à; prendre? et bien Hephaistox peut vous aider si :"]
       [:ul
        [:li "Vous doutez de la rentabilité d’un nouvel investissement?"]
        [:li
         "Vous cherchez à; vous organiser différemment pour diminuer votre consommation énergétique, mais vous avez des doutes sur la pertinence de vos solutions?"]
        [:li "Vous cherchez des alternatives pour minimiser votre perte matière?"]
        [:li "Vous pensez pouvoir diminuer votre stock mais ne savez pas dans quelles conditions?"]
        [:li
         "Vous industrialisez un nouveau produit et cherchez à; anticiper son impact sur l’atelier existant?"]]
       [:p "Ainsi que des questions qui vous tiennent à; coeur…"]
       [:p
        "Vous avez des experts et leurs connaissances est irremplaçable, mais quand la question est complexe, que ses impacts font intervenir plusieurs domaines d’expertise, quand la situation est nouvelle, nous pouvons aider vos experts et ceux qui doivent prendre la décision."]
       [:p
        "Hephaistox est votre partenaire, au-delà; d’une simple prestation de consultants - nous sommes des artisans de la chaîne logistique, fabriquant des solutions sur mesure, avec du logiciel de qualité. Nous utilisons une méthodologie qui a été éprouvée par nos années d’expériences. Elle consiste dans les étapes suivantes:"]
       [:ul
        [:li
         [:b "Définir le problème : "]
         "Grâce à; notre connaissance de la chaîne logistique et des expériences industrielles variées, nous pouvons vous aider à; bien poser votre question - un problème bien posé est à; moitié résolu,"]
        [:li
         [:b "Guider votre modélisation : "]
         "Nos compétences en modélisation vont vous guider à; commencer par les choses les plus importantes d’abord,"]
        [:li
         [:b "Résolution : "]
         "Nous mettons en oeuvre nos méthodes et outils dans le cadre convenu ensemble."]
        [:li
         [:b "Faire prendre la décision : "]
         "Et faire converger tous les acteurs sur une décision (quel que soit le métier / des opérateurs aux décideurs)."]]
       [:p
        "Cette approche est l’essence de l’approche scientifique, elle vous donnera confiance dans votre prise de décision, avec des éléments précis."]
       [:p
        "Planifiez un rendez-vous avec nous et laissez Hephaistox être le catalyseur pour amener la chaîne logistique au prochain niveau."]
       [:p
        "Pour que notre collaboration soit un succès, nous pensons important que vous n’ayez qu’un seul point de contact, pilotant et maîtrisant l’ensemble du périmètre de la solution: de l’analyse du fonctionnement de votre entreprise, de l’étude de faisabilité, et pour finir, de la création de la solution finale. Ainsi, nous nous assurerons que la solution créée corresponde à votre problème, que ce qui vous rends unique soit préservé et nous nous assurerons que cette solution fonctionne avec votre équipe."]
       [:p
        "Pour que cela soit possible, nous travaillons de manière rapprochée avec votre équipe, en comprenant leurs besoins et leur façon de travailler, pour être sûr que la solution créée soit non seulement comprise mais adoptée."]
       [:p
        "Pour être flexible et permettre la croissance, notre offre est divisée en quatre étapes. Ainsi, vous pouvez être sûr qu’à chaque étape vous serez satisfait de notre travail. Ce sera aussi l’occasion de stopper et d’être sûr que nous sommes tous (vous et nous) engagés sur les résultats, sur le retour sur investissement. En particulier, nous examinerons ensemble si la prochaine étape a du sens pour vous (toutes nos décisions sont argumentées par des faits, des données). Ces étapes sont naturellement séparées, d’un point de vue paiement, valeurs et documents produits. Nous visons juste la meilleure solution pour vous, même si cela signifie que nous ne serons pas nécessaires sur les étapes suivantes."])]))

(def digital-twin-map
  {:title {:en "Two-minute pitch"
           :fr "Discours en deux minutes"}
   :description {:en "Two-minute pitch"
                 :fr "Discours en deux minutes"}
   :handler digital-twin-body})
