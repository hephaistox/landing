(ns landing.article.hephaistox
  (:require
   [auto-web.components.img  :refer [cimg images]]
   [auto-web.components.link :refer [cspan-link]]))

(def links
  (->> [{:link-id :agile-manifesto
         :url "https://agilemanifesto.org/iso/fr/manifesto.html"}]
       (mapv (fn [x] [(:link-id x) x]))
       (into {})))

(defn hephaistox-body
  [_http-request l]
  (case l
    :en
    [:article.text
     [:h1 "Hephaistox brand"]
     [:div.w3-center (cimg {:class "w3-center"} :medium (:hephaistox-logo images))]
     [:h2 "What is Hephaistox?"]
     [:p
      "Hephaestus is a Greek god who inspired early industries thousands of years ago. He is the god of tailor-made solutions, a god of craftsmanship whose products are of great value and stand the test of time. He is also a god of automation and innovative solutions."]
     [:p
      "So, we named our company after him, and we chose his name and blended it with the letter x, which stands for flexibility, growth, and inclusion."]
     [:p "We prefer long-term solutions, prioritizing the quality and reliability of our products."]
     [:h2 "Priority to local cooperation"]
     [:p
      "Our goal is to provide high-quality solutions to small and medium-sized industries. We target these companies because we firmly believe that Europe's strength and self-sufficiency lies in its local businesses. We are committed to offering these companies solutions that are often reserved for large corporations."]
     [:p "Helping local businesses grow also benefits all businesses in the local market"]
     [:h2 "No bullshit"]
     [:p "Many companies boast great virtues but few actually follow through on them."]
     [:p
      "We keep our promises, and for each value displayed on this page we take concrete actions to make them real."]
     [:p
      "We don't believe in big words and vague concepts, so we will talk with you about facts and possibilities."]
     [:h2 "Knowledge sharing"]
     [:p "We are committed to sharing our knowledge to foster growth and innovation for all."]
     [:p
      "Most of our work is public, publishing articles, speaking at events, and open-sourcing most of our code."]
     [:p
      "We are excited to enable collaboration and accelerate shared understanding of supply chain, business, and IT."]
     [:h2 "Transparency and trust"]
     [:p
      "We value transparency and trust, which is why from the very beginning of our work together we want to be clear about the costs and expected return values ​​at each stage of the project."]
     [:h2 "The culture of feedback"]
     [:p
      "We are always happy to give and receive feedback. We believe that feedback is the cornerstone of improvement."]
     [:h2 "Growth"]
     [:p
      "We believe that the growth of a company is linked to the growth of its people. This is also reflected in the quality of its products and services. That's why we make sure we have time to grow our knowledge and set goals that are both company-wide and individual."]
     [:h2 "Agile manifesto"]
     [:p
      "We endorse the Agile Manifesto "
      (cspan-link {} (:agile-manifesto links) "manifeste agile")
      ". The following excerpts will be meaningful to our clients:"]
     [:ul
      [:li
       "Our highest priority is to satisfy the customer by quickly and consistently delivering high-value features."]
      [:li
       "Welcome changing requirements, even late in the project. Agile processes leverage change to give the customer a competitive advantage."]
      [:li
       "Deliver working software frequently with cycles of a few weeks to a few months, with a preference for shorter ones."]
      [:li
       "Users or their representatives and developers must work together daily throughout the project."]
      [:li "Working software is the primary measure of progress."]
      [:li
       "Agile processes encourage a sustainable pace of development. Together, sponsors, developers, and users should be able to maintain a constant pace indefinitely."]
      [:li "Continuous attention to technical excellence and good design reinforces Agility."]
      [:li
       "Simplicity – that is, the art of minimizing the amount of unnecessary work – is essential."]]]
    :fr
    [:article.text
     [:h1 "La marque Hephaistox"]
     [:div.w3-center (cimg {:class "w3-center"} :medium (:hephaistox-logo images))]
     [:h2 "Qu'est-ce qu'Hephaistox?"]
     [:p
      "Hephaistos est un Dieu grec, il a été une inspiration pour les premières industries il y a des milliers d’années, comme le Dieu des solutions faites sur mesures, un Dieu de l’artisanat dont les produits sont d’une grande valeur, qui résiste au temps. Un Dieu de l’automatisation et des solutions innovantes."]
     [:p
      "Ainsi, nous avons nommé notre entreprise en son honneur, et nous avons choisis son nom que nous avons mélangé avec la lettre x, qui signifie la flexibilité, la croissance et l’inclusion."]
     [:p
      "Nous préférons les solutions longs termes, en priorisant la qualité et la fiabilité de nos produits."]
     [:h2 "Priorité à la coopération locale"]
     [:p
      "Notre objectif est de fournir des solutions de haut niveau à des petites et moyennes industries. Nous visons ces entreprises car nous croyons fermement que la force et l’auto suffisance de l’Europe réside dans ses entreprises locales. Nous sommes engagés à offrir à ces entreprises des solutions qui sont souvent réservées aux grandes entreprises."]
     [:p
      "Aider à la croissance des entreprises locales bénéficie aussi à toutes les entreprises du marché local"]
     [:h2 "Pas de foutaises"]
     [:p "Beaucoup d’entreprises affichent de grandes vertus mais peu les suivent réellement."]
     [:p
      "Nous respectons nos promesses, et pour chaque valeur affiché sur cette page nous menons des actions concrètes pour les rendre réelles."]
     [:p
      "Nous ne croyons pas les grands mots et les concepts fumeux, c’est pourquoi nous parlerons avec vous de faits et de choses possibles."]
     [:h2 "Partage de connaissance"]
     [:p
      "Nous sommes engagés à partager nos connaissances pour favoriser croissance et innovations de tous."]
     [:p
      "La plus grande partie de notre travail est publique, en publiant des articles, conférences dans des événements et en rendant open source la plupart de notre code."]
     [:p
      "Nous sommes heureux de permettre la collaboration et d’accélérer la compréhension commune de la chaîne logistique, du business et de l’informatique."]
     [:h2 "Transparence et confiance"]
     [:p
      "Nous accordons de la valeur à la transparence et à la confiance, c’est pourquoi dès le début de notre travail ensemble, nous voulons être clair sur les coûts et valeurs attendues en retour à chaque étape du projet."]
     [:h2 "La culture du retour d’information"]
     [:p
      "Nous sommes toujours content de donner et recevoir des retours d’information. Nous croyons que les retours d’informations sont les pierres angulaires de l’amélioration."]
     [:h2 "Croissance"]
     [:p
      "Nous croyons que la croissance d’une entreprise est liée à la croissance des personnes qui la compose. Ce qui se reflète aussi sur la qualité de ses produits et services. C’est pourquoi nous nous assurons d’avoir du temps pour faire croître nos connaissances et de mettre en place des objectifs qui sont à la fois pour l’ensemble de l’entreprise et pour chacun."]
     [:h2 "Agile manifesto"]
     [:p
      "Nous approuvons le "
      (cspan-link {} (:agile-manifesto links) "manifeste agile")
      ". Les extraits suivants feront sens pour nos clients:"]
     [:ul
      [:li
       "Notre plus haute priorité est de satisfaire le client en livrant rapidement et régulièrement des fonctionnalités à grande valeur ajoutée."]
      [:li
       "Accueillez positivement les changements de besoins, même tard dans le projet. Les processus Agiles exploitent le changement pour donner un avantage compétitif au client."]
      [:li
       "Livrez fréquemment un logiciel opérationnel avec des cycles de quelques semaines à quelques mois et une préférence pour les plus courts."]
      [:li
       "Les utilisateurs ou leurs représentants et les développeurs doivent travailler ensemble quotidiennement tout au long du projet."]
      [:li "Un logiciel opérationnel est la principale mesure d’avancement."]
      [:li
       "Les processus Agiles encouragent un rythme de développement soutenable. Ensemble, les commanditaires, les développeurs et les utilisateurs devraient être capables de maintenir indéfiniment un rythme constant."]
      [:li
       "Une attention continue à l'excellence technique età une bonne conception renforce l’Agilité."]
      [:li
       "La simplicité – c’est-à-dire l’art de minimiser la quantité de travail inutile – est essentielle."]]]))

(def hephaistox-map
  {:title {:en "Hephaistox trademark"
           :fr "Marque Hephaistox"}
   :description {:en "Hephaistox trademark"
                 :fr "Marque Hephaistox"}
   :handler hephaistox-body})
