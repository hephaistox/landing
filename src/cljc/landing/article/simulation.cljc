(ns landing.article.simulation)

(defn simulation-body
  [_http-request l]
  (case l
    :en
    [:article.text
     [:h1 "Two-minute pitch"]
     [:p "Welcome to Hephaistox, the supply chain craftsman!"]
     [:p
      "Are you an industrial? Do you have some difficult questions to answer? So think about working with Hephaistox. Mati and Anthony can help you if:"]
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
      "That approach - using the scientific method - will give you confidence in your decision-making, and give precise decisions."]]
    :fr
    [:article.text
     [:h1 "Discours en deux minutes"]
     [:p "Bienvenue &agrave; Hephaistox, l’artisan de la cha&icirc;ne logistique."]
     [:p
      "Vous êtes industriels? Vous avez une décision difficile &agrave; prendre? et bien pensez &agrave; Hephaistox. Mati et Anthony (moi-même) pouvons vous aider si:"]
     [:ul
      [:li "Vous doutez de la rentabilité d’un nouvel investissement?"]
      [:li
       "Vous cherchez &agrave; vous organiser différemment pour diminuer votre consommation énergétique, mais vous avez des doutes sur la pertinence de vos solutions?"]
      [:li "Vous cherchez des alternatives pour minimiser votre perte matière?"]
      [:li "Vous pensez pouvoir diminuer votre stock mais ne savez pas dans quelles conditions?"]
      [:li
       "Vous industrialisez un nouveau produit et cherchez &agrave; anticiper son impact sur l’atelier existant?"]]
     [:p "Ainsi que des questions qui vous tiennent &agrave; coeur…"]
     [:p
      "Vous avez des experts et leurs connaissances est irremplaçable, mais quand la question est complexe, que ses impacts font intervenir plusieurs domaines d’expertise, quand la situation est nouvelle, nous pouvons aider vos experts et ceux qui doivent prendre la décision."]
     [:p
      "Hephaistox est votre partenaire, au-del&agrave; d’une simple prestation de consultants - nous sommes des artisans de la chaîne logistique, fabriquant des solutions sur mesure, avec du logiciel de qualité. Nous utilisons une méthodologie qui a été éprouvée par nos années d’expériences. Elle consiste dans les étapes suivantes:"]
     [:ul
      [:li
       [:b "Définir le problème:"]
       "Grâce &agrave; notre connaissance de la chaîne logistique et des expériences industrielles variées, nous pouvons vous aider &agrave; bien poser votre question - un problème bien posé est &agrave; moitié résolu,"]
      [:li
       [:b "Guider votre modélisation: "]
       "Nos compétences en modélisation vont vous guider &agrave; commencer par les choses les plus importantes d’abord,"]
      [:li
       [:b "Résolution: "]
       "Nous mettons en oeuvre nos méthodes et outils dans le cadre convenu ensemble."]
      [:li
       [:b "Faire prendre la décision: "]
       "Et faire converger tous les acteurs sur une décision (quel que soit le métier / des opérateurs aux décideurs)."]]
     [:p
      "Cette approche est l’essence de l’approche scientifique, elle vous donnera confiance dans votre prise de décision, avec des éléments précis."]
     [:p
      "Planifiez un rendez-vous avec nous et laissez Hephaistox être le catalyseur pour amener la chaîne logistique au prochain niveau."]]))

(def simulation-map
  {:title {:en "Two-minute pitch"
           :fr "Discours en deux minutes"}
   :description {:en "Two-minute pitch"
                 :fr "Discours en deux minutes"}
   :handler simulation-body})
