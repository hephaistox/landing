(ns landing.article.rivalis
  (:require
   [auto-web.components.img  :refer [cimg]]
   [auto-web.components.link :refer [link-opts]]
   [landing.pages.article    :refer [lsection rsection]]
   [landing.routes           :as lroutes]))

(defn icon
  [path label]
  [:div.w3-center
   [:svg {:xmlns "http://www.w3.org/2000/svg"
          :width "4em"
          :height "4em"
          :fill "currentColor"
          :viewBox "0 0 640 640"}
    [:path {:d path}]]
   [:div label]])

(def restaurant-icon
  (icon
   "M127.9 78.4C127.1 70.2 120.2 64 112 64C103.8 64 96.9 70.2 96 78.3L81.9 213.7C80.6 219.7 80 225.8 80 231.9C80 277.8 115.1 315.5 160 319.6L160 544C160 561.7 174.3 576 192 576C209.7 576 224 561.7 224 544L224 319.6C268.9 315.5 304 277.8 304 231.9C304 225.8 303.4 219.7 302.1 213.7L287.9 78.3C287.1 70.2 280.2 64 272 64C263.8 64 256.9 70.2 256.1 78.4L242.5 213.9C241.9 219.6 237.1 224 231.4 224C225.6 224 220.8 219.6 220.2 213.8L207.9 78.6C207.2 70.3 200.3 64 192 64C183.7 64 176.8 70.3 176.1 78.6L163.8 213.8C163.3 219.6 158.4 224 152.6 224C146.8 224 142 219.6 141.5 213.9L127.9 78.4zM512 64C496 64 384 96 384 240L384 352C384 387.3 412.7 416 448 416L480 416L480 544C480 561.7 494.3 576 512 576C529.7 576 544 561.7 544 544L544 96C544 78.3 529.7 64 512 64z"
   "Restaurants"))

(def artisan-btp-icon
  (icon
   "M384 328L384 128C384 110.3 369.7 96 352 96L288 96C270.3 96 256 110.3 256 128L256 328C256 341.3 245.3 352 232 352C218.7 352 208 341.3 208 328L208 142.1C122 173.8 64 255.8 64 352L64 416L576 416L576 352C575 256.8 517.6 174.3 432 142.2L432 328C432 341.3 421.3 352 408 352C394.7 352 384 341.3 384 328zM72 464C49.9 464 32 481.9 32 504C32 526.1 49.9 544 72 544L568 544C590.1 544 608 526.1 608 504C608 481.9 590.1 464 568 464L72 464z"
   "Artisan du BTP"))

(def commerces-icon
  (icon
   "M53.5 245.1L110.3 131.4C121.2 109.7 143.3 96 167.6 96L472.5 96C496.7 96 518.9 109.7 529.7 131.4L586.5 245.1C590.1 252.3 592 260.2 592 268.3C592 295.6 570.8 318 544 319.9L544 512C544 529.7 529.7 544 512 544C494.3 544 480 529.7 480 512L480 320L384 320L384 496C384 522.5 362.5 544 336 544L144 544C117.5 544 96 522.5 96 496L96 319.9C69.2 318 48 295.6 48 268.3C48 260.3 49.9 252.3 53.5 245.1zM160 320L160 432C160 440.8 167.2 448 176 448L304 448C312.8 448 320 440.8 320 432L320 320L160 320z"
   "Commerces"))

(def profession-liberales-icon
  (icon
   "M320 72C253.7 72 200 125.7 200 192C200 258.3 253.7 312 320 312C386.3 312 440 258.3 440 192C440 125.7 386.3 72 320 72zM380 384.8C374.6 384.3 369 384 363.4 384L276.5 384C270.9 384 265.4 384.3 259.9 384.8L259.9 452.3C276.4 459.9 287.9 476.6 287.9 495.9C287.9 522.4 266.4 543.9 239.9 543.9C213.4 543.9 191.9 522.4 191.9 495.9C191.9 476.5 203.4 459.8 219.9 452.3L219.9 393.9C157 417 112 477.6 112 548.6C112 563.7 124.3 576 139.4 576L500.5 576C515.6 576 527.9 563.7 527.9 548.6C527.9 477.6 482.9 417.1 419.9 394L419.9 431.4C443.2 439.6 459.9 461.9 459.9 488L459.9 520C459.9 531 450.9 540 439.9 540C428.9 540 419.9 531 419.9 520L419.9 488C419.9 477 410.9 468 399.9 468C388.9 468 379.9 477 379.9 488L379.9 520C379.9 531 370.9 540 359.9 540C348.9 540 339.9 531 339.9 520L339.9 488C339.9 461.9 356.6 439.7 379.9 431.4L379.9 384.8z"
   "Professions libérales"))

(def industrie-icon
  (icon
   "M96 96C78.3 96 64 110.3 64 128L64 496C64 522.5 85.5 544 112 544L528 544C554.5 544 576 522.5 576 496L576 216.2C576 198 556.6 186.5 540.6 195.1L384 279.4L384 216.2C384 198 364.6 186.5 348.6 195.1L192 279.4L192 128C192 110.3 177.7 96 160 96L96 96z"
   "Industrie"))

(def improvement-icon
  (icon
   "M61.911 505.089a27 27 90 010-38.178l81-81a27 27 90 0134.074-3.375l62.586 41.715 65.34-65.34a27 27 90 0138.178 38.178l-81 81a27 27 90 01-34.074 3.375L165.429 439.749l-65.34 65.34a27 27 90 01-38.178 0ZM594 81V567a27 27 90 01-27 27H81a27 27 90 010-54H540V216H108v108a27 27 90 01-54 0V81A27 27 90 0181 54H567A27 27 90 01594 81ZM108 162H540V108H108Zm289.089 181.089 27-27a27 27 90 00-38.178-38.178l-27 27a27 27 90 1038.178 38.178Z"
   nil))

(def planning-icon
  (icon
   "M603.75 91.8309h-94.5042V34.0704c0-8.7003-7.0497-15.75-15.75-15.75-8.7003 0-15.75 7.0497-15.75 15.75v57.7605H141.7542V34.0704c0-8.7003-7.0518-15.75-15.75-15.75-8.7003 0-15.75 7.0497-15.75 15.75v57.7605H15.75c-8.7003 0-15.75 7.0497-15.75 15.75v477.8487c0 8.6982 7.0497 15.75 15.75 15.75h588c8.7003 0 15.75-7.0518 15.75-15.75V107.5809C619.5 98.8806 612.4503 91.8309 603.75 91.8309zM352.7979 273.7035v75.4341h-86.0958v-75.4341H352.7979zM588 349.1355h-86.1042v-75.4341H588V349.1355zM470.3958 349.1355h-86.0979v-75.4341h86.0979V349.1355zM266.7021 380.6355h86.0958v75.4236h-86.0958V380.6355zM266.7021 487.5591h86.0958v82.1205h-86.0958V487.5591zM384.2979 487.5591h86.0979v82.1205h-86.0979V487.5591zM384.2979 456.0591v-75.4236h86.0979v75.4236H384.2979zM501.8958 380.6355H588v75.4236h-86.1042V380.6355zM110.2542 123.3309v57.7689c0 8.7003 7.0497 15.75 15.75 15.75 8.6982 0 15.75-7.0497 15.75-15.75V123.3309h335.9916v57.7689c0 8.7003 7.0497 15.75 15.75 15.75 8.7003 0 15.75-7.0497 15.75-15.75V123.3309H588v118.8726H31.5V123.3309H110.2542zM31.5 273.7035h203.7021v295.9761H31.5V273.7035zM501.8958 569.6796v-82.1205H588v82.1205H501.8958z"
   nil))

(def first-meeting-icon
  (icon
   "M586.7825 436.3769c-2.907-5.4972-6.5737-10.9771-10.9365-16.4894-9.9424-12.5628-23.4633-25.2776-39.7729-38.171-11.3784-8.9958-23.4749-17.5682-35.5621-25.4748-7.2697-4.756-12.9816-8.2696-16.3827-10.2625-28.7088-16.3467-61.7839-14.1798-78.8649 13.3481-.7934.9419-1.8015 2.1356-2.9777 3.5194-3.2155 3.7816-6.4786 7.5586-9.5723 11.0606-.6682.7552-.6682.7552-1.3352 1.5057-5.1353 5.7687-9.8356 10.6233-9.6628 10.5061-18.9068 12.796-31.2144 11.7891-47.8836-4.8813L213.4122 260.6207c-16.6692-16.6692-17.6772-28.9768-4.8732-47.8952-.1241.1833 4.7305-4.517 10.4992-9.6524.7505-.667.7505-.667 1.5057-1.3352 3.502-3.0937 7.279-6.3568 11.0606-9.5723 1.3839-1.1762 2.5764-2.1854 3.5194-2.9777 27.528-17.081 29.6983-50.1491 13.5024-78.597-2.1472-3.6691-5.6608-9.3798-10.4168-16.6506-7.9066-12.0872-16.479-24.1837-25.4748-35.5621-12.8922-16.3096-25.6082-29.8306-38.171-39.7729-5.5123-4.3628-10.9933-8.0295-16.4975-10.9411-25.6093-13.5372-57.0094-8.823-77.5066 11.6348L53.9562 45.9267c-88.2516 88.2516-63.9288 193.4254 41.5326 298.8868l77.0588 77.0832 77.0913 77.0669c105.4591 105.4591 210.634 129.782 298.8926 41.5222l26.586-26.6104C595.602 493.4304 600.336 461.9793 586.7825 436.3769zM540.1296 478.87l-26.6034 26.6278c-64.5656 64.5656-140.3113 47.0484-228.8935-41.5326l-77.089-77.0634-77.0553-77.0797c-88.5846-88.5846-106.1017-164.3302-41.528-228.904l26.586-26.6104c5.1133-5.104 12.9618-6.2814 19.3824-2.8872 2.6402 1.3966 5.6446 3.4069 8.9181 5.9972 9.0747 7.1816 19.343 18.1018 30.0591 31.6576 7.9889 10.1059 15.7308 21.0308 22.8833 31.9626 4.2792 6.5424 7.3741 11.5733 8.9784 14.3109 4.4648 7.8462 4.1899 11.2752 2.8733 12.0153l-3.7317 2.5717c-1.095.9129-2.9708 2.4905-5.372 4.5321-4.0008 3.4034-8.0052 6.8614-11.767 10.1848-.8213.7273-.8213.7273-1.6449 1.4581-9.7904 8.7162-15.3178 14.0673-18.5704 18.8674-25.9515 38.3461-22.7882 76.9973 10.8576 110.6431l120.4173 120.4173c33.6458 33.6458 72.297 36.8103 110.6327 10.8657 4.8105-3.2596 10.1616-8.787 18.8778-18.5774.7308-.8224.7308-.8224 1.4581-1.6449 3.3234-3.7619 6.7825-7.7662 10.1848-11.767 2.0416-2.4 3.6192-4.2758 4.5321-5.372l2.5717-3.7317c.7389-1.3166 4.169-1.5904 12.2832 3.0288 2.4696 1.45 7.5006 4.5449 14.043 8.8241 10.933 7.1514 21.8567 14.8932 31.9638 22.8833 13.5558 10.7161 24.4748 20.9844 31.6564 30.0591 2.5903 3.2724 4.6006 6.2779 6.0007 8.9239C546.4226 465.9244 545.2429 473.766 540.1296 478.87z"
   nil))

(def arrow-icon
  (icon
   "M318.08 238.56 318.08 79.52 397.6 79.52 636.16 318.08 397.6 556.64 318.08 556.64 318.08 397.6 0 397.6 0 238.56 318.08 238.56Z"
   nil))

(def phone-icon
  (icon
   "M37.6 188V37.6H263.2V188L169.2 282 319.6 432.4 413.6 338.4H564V564H413.6C205.9408 564 37.6 395.6573 37.6 188Z"
   nil))

(def testimony-icon
  (icon
   "M580 40H60C38 40 20 58 20 80v360c0 22 18 40 40 40h340v71.72c0 17.82 21.54 26.74 34.14 14.14L520 480h60c22 0 40-18 40-40V80C620 58 602 40 580 40zM420 320H220c-11.06 0-20-8.96-20-20s8.94-20 20-20h200c11.06 0 20 8.96 20 20S431.06 320 420 320zM420 240H220c-11.06 0-20-8.96-20-20s8.94-20 20-20h200c11.06 0 20 8.96 20 20S431.06 240 420 240z"
   nil))

(def question-mark-icon
  (icon
   "M372.3697 80.1773c-12.3817-23.5348-31.7673-43.1441-55.7815-57.3682C292.5844 8.648 263.6089.0009 232.1612.0009c-38.7637-.0959-70.97 10.0035-95.2775 23.9897-24.4043 13.9364-34.9266 30.1627-34.9266 30.1627-4.1012 3.5588-6.4146 8.7364-6.3177 14.1526.1109 5.4238 2.6085 10.5148 6.8366 13.897l33.777 27.0532c6.8846 5.5122 16.7376 5.3054 23.3825-.4869 0 0 4.1492-7.4993 17.1522-14.9272 13.0754-7.379 30.0283-13.3226 55.3735-13.4025 22.106-.0479 41.3807 8.2015 54.535 19.474 6.5349 5.5845 11.4163 11.8384 14.4083 17.5592 3.0155 5.7678 4.1163 10.81 4.1012 14.6396-.0639 12.94-2.5775 21.4038-6.2068 28.6155-2.7683 5.3853-6.3817 10.1642-11.0337 14.7994-6.941 6.941-16.3541 13.3546-26.916 19.2503-10.5703 5.9671-21.9631 11.2161-33.4753 17.5592-13.1393 7.2756-27.0438 17.7265-37.3189 33.4114-5.1221 7.7541-9.1274 16.6248-11.671 25.9825-2.5775 9.3662-3.7412 19.1873-3.7412 29.2067 0 10.6897 0 19.4655 0 19.4655 0 10.0759 8.1686 18.2454 18.2454 18.2454h43.9563c10.0759 0 18.2454-8.1695 18.2454-18.2454 0 0 0-8.7758 0-19.4655 0-3.8606.439-6.3497.862-7.9298.7266-2.3613 1.1336-2.9516 2.3218-4.3879 1.2126-1.3649 3.6613-3.4536 8.1761-5.9511 6.5979-3.7092 17.2001-8.7194 29.2058-15.2054 17.9737-9.8286 39.825-23.1672 58.2537-45.1783 9.1669-10.9848 17.2716-24.2125 22.8636-39.4499 5.6409-15.2374 8.6875-32.3651 8.6724-50.7224C385.6284 113.5153 380.5863 95.8527 372.3697 80.1773zM215.0805 382.0536c-27.4029 0-49.6207 22.2254-49.6207 49.6207 0 27.3878 22.2188 49.6057 49.6207 49.6057 27.3878 0 49.5972-22.2178 49.5972-49.6057C264.6777 404.279 242.4683 382.0536 215.0805 382.0536z"
   nil))

(def links
  (->> [{:link-id :agile-manifesto
         :url "https://agilemanifesto.org/iso/fr/manifesto.html"}
        {:link-id :henrri
         :url "https://www.henrri.com/"}
        {:link-id :rivalis
         :url "https://www.rivalis.fr/"}
        {:link-id :contact
         :url "/articles/rivalis#contact"}]
       (mapv (fn [x] [(:link-id x) x]))
       (into {})))

(defn contact-button
  []
  [:a
   (link-opts (:contact links))
   [:div.w3-button.w3-orange.w3-ripple.w3-text-white.w3-large.w3-bold {:style {:user-select "none"}}
    "Faire le bilan gratuit"]])

(def images
  (->> [{:url "/images/anthony.jpeg"
         :alt "Portrait anthony"
         :img-id :anthony}
        {:url "/images/rivalis/etude_apports.png"
         :alt "Etude sur l'apport de Rivalis"
         :img-id :etude-apports-rivalis}
        {:url "/images/logo/rivalis.png"
         :alt "Rivalis"
         :img-id :rivalis-logo}
        {:url "/images/logo/henrri.png"
         :alt "Henrri"
         :img-id :henrri-logo}]
       (mapv (fn [link] [(:img-id link) link]))
       (into {})))

(defn rivalis-body
  [_http-request l]
  (case l
    :en
    [(lsection
      "Advice for small business"
      "For managers in Puy-de-Dôme and Allier, France"
      (cimg {} :medium (:hephaistox-logo lroutes/images))
      [:p
       "I support managers of "
       [:b "Very Small Enterprises (VSEs)"]
       " to help them manage their businesses. By becoming your co-pilot, I help you gain peace of mind, respond to unforeseen events, and achieve your goals."])
     (rsection "Your companion for small enterprise"
               "This offer is reserved for French local companies"
               nil
               [:p "Consequently, that offer is in french version only."])]
    :fr
    [(lsection
      "Conseils aux TPE"
      "Pour les dirigeants du Puy-de-Dôme et Allier"
      (cimg {} :medium (:hephaistox-logo lroutes/images))
      [:p
       "Dirigeants de "
       [:b "Très Petites Entreprises (TPEs)"]
       ", je vous accompagne dans le pilotage de votre entreprise. En devenant votre copilote, je vous fais gagner en sérénité, à réagir aux imprévus, à atteindre vos objectifs."]
      [:div.w3-center (contact-button)])
     (rsection
      "C'est quoi"
      "Répondre à ces 6 questions:"
      question-mark-icon
      [:div
       [:p
        "Je mets des outils et des méthodes à votre disposition, ils sont éprouvés depuis 30 ans, et vous permettront de répondre sereinement aux questions ci-dessous :"]
       [:table.w3-table.w3-auto {:style {:width "auto"}}
        [:tr [:td [:i.fa.fa-solid.fa-scale-balanced]] [:td "Où en est mon entreprise ?"]]
        [:tr [:td [:i.fa.fa-solid.fa-route]] [:td "Où je vais ?"]]
        [:tr [:td [:i.fa.fa-solid.fa-euro-sign]] [:td "Mes devis sont-ils rentables ?"]]
        [:tr [:td [:i.fas.fa-file-invoice-dollar]] [:td "Où en sont mes impayés ?"]]
        [:tr [:td [:i.fa.fa-solid.fa-user-tie]] [:td "Puis-je embaucher ?"]]
        [:tr [:td [:i.fa.fa-solid.fa-money-bill-trend-up]] [:td "Puis-je investir ?"]]]]
      [:br]
      [:div.w3-center (contact-button)])
     (lsection "4 secteurs d’intervention"
               "Vos domaines d'activité"
               (cimg {} :medium (:hephaistox-logo lroutes/images))
               [:div.w3-flex {:style {:flex-wrap "wrap"
                                      :justify-content "center"
                                      :gap "1em"}}
                restaurant-icon
                artisan-btp-icon
                commerces-icon
                profession-liberales-icon
                industrie-icon]
               [:br]
               [:div.w3-center (contact-button)])
     (rsection
      "Besoin d'être accompagné ?"
      "Les symptômes"
      [:div.w3-center [:i.fa.fa-handshake.w3-xxxlarge]]
      [:p "Profitez de mon accompagnement si vous êtes concernés par les symptômes ci-dessous:"]
      [:ul
       [:li "Vous vous sentez seuls dans la gestion de votre entreprise ?"]
       [:li "Vous manquez de temps pour réfléchir ?"]
       [:li "Vous avez besoin d'être conforté dans vos décisions ? "]
       [:li "Vous êtes fatigué, parfois découragé ?"]
       [:li "Vous avez des idées mais pas le temps de les mettre en oeuvre ?"]]
      [:br]
      [:div.w3-center (contact-button)])
     (lsection
      "La méthode"
      "Améliorez vos performances"
      improvement-icon
      [:div
       [:p
        "Faites le rendez-vous découverte pour mieux comprendre, mais sachez que la méthode est faite de:"]
       [:ul
        [:li
         [:b "Un GPS et un copilote"]
         [:ul
          [:li
           "Le GPS c'est Henrri, un outil simple"
           " inclus dans l'offre, qui vous permet de répondre aux six questions, jour après jour"]
          [:li
           "Le copilote, c'est moi, je vous permets de faire les prévisionnels, faire les rendez-vous mensuels, et les plans d'actions"]]
         [:br]]
        [:li
         [:b "Concrètement, vous améliorerez :"]
         [:ul [:li "Votre marge"] [:li "Votre chiffre d'affaires"] [:li "Votre temps libre"]]
         [:br]]
        [:li
         [:b "Avec un accompagnement"]
         [:ul
          [:li "Fait de rendez-vous réguliers"]
          [:li "Sur le temps long"]
          [:li "Qui vous apporte un regard extérieur"]
          [:li "D'un entrepreneur qui parle à un entrepreneur"]
          [:li "Par un conseil proche de vous et disponible"]]
         [:br]]]]
      [:div.w3-center (contact-button)])
     (rsection
      "Comment cela va se passer ?"
      "Nos interactions"
      planning-icon
      nil
      [:div
       [:ul
        [:li [:b "Premier appel:"] [:ul [:li "On se présente, on convient d'un rendez-vous"]] [:br]]
        [:li
         [:b "Rendez-vous découverte:"]
         [:ul [:li "On se rencontre, je comprends votre objectif, on définit un prévisionnel"]]
         [:br]]
        [:li
         [:b "Rendez-vous mensuel:"]
         [:ul
          [:li "Nous suivons les devis, factures, encaissements"]
          [:li "Nous identifions les écarts au prévisionnel"]
          [:li "Nous organisons des actions"]]
         [:br]]
        [:li
         [:b "Sujets ponctuels:"]
         [:ul [:li "Au besoin, on effectue les actions entre deux rendez-vous"]]
         [:br]]
        [:li
         [:b "Prestations complémentaires:"]
         [:ul [:li "Création d'entreprise, revue de la carte d'un restaurant, ..."]]
         [:br]]]]
      [:div.w3-center (contact-button)])
     (lsection
      "Méthode Rivalis"
      "Le choix d'un réseau"
      [:a
       (link-opts (:rivalis links))
       [:div
        (cimg {} :large (:rivalis-logo images))
        [:div.w3-button.w3-ripple.w3-large.w3-bold {:style {:user-select "none"}}
         "Découvrez Rivalis"]]]
      [:p
       "Pour vous offir une méthode robuste et éprouvée, j'ai choisi d'intégrer le réseau Rivalis. Ce réseau existe depuis 1994, cela fait donc trois décennies que Rivalis aide les TPEs. Aujourd'hui, nous sommes "
       [:b "803 conseillers"]
       " sur toute la France et 211 314 utilisateurs de nos services (données au 31 décembre 2024)."]
      [:div.w3-flex
       [:div
        [:a
         (link-opts (:henrri links))
         [:div
          (cimg {} :large (:henrri-logo images))
          [:div.w3-button.w3-ripple.w3-large.w3-bold {:style {:user-select "none"}}
           "Découvrez Henrri"]]]]
       [:p
        "Henrri est un outil de gestion des devis, factures et encaissements. Nous utiliserons cet outil pour faire l'analyse de votre Entreprise."]]
      [:p "En choisissant Rivalis, je vous offre :"]
      [:ul
       [:li "une méthode éprouvée"]
       [:li "une qualité garantie grâce à un coaching expert de l’équipe centrale Rivalis"]]
      [:div.w3-center (contact-button)])
     (rsection
      "Résultats démontrés"
      "Enquête (*)"
      (cimg {} :medium (:etude-apports-rivalis images))
      [:ul
       [:li "Sérénité: les clients s'auto-évaluent 4.2/10 avant Rivalis, puis 8.2/10 avec Rivalis"]
       [:li "+45% de congés par an"]
       [:li "+4 heures de temps libre"]
       [:li "+105% de résultat d'exploitation annuel"]
       [:li "+46% de solde bancaire"]
       [:li "+22% de chiffre d'affaires"]
       [:li "7148 € d'investissement par entreprise."]]
      [:div.w3-center (contact-button)]
      [:p.w3-small
       "(*) Enquête réalisée sous contrôle d’huissier par téléphone et en face à face, menée sur 254 clients (ayant 1 an et plus d’accompagnement Rivalis), de mai à novembre 2022."])
     (lsection
      "Témoignage"
      "Comment les clients parle de Rivalis ?"
      testimony-icon
      [:div.w3-row
       [:div {:style {:position "relative"
                      :padding-bottom "56.25%" ;; 16:9
                      :height 0
                      :overflow "hidden"
                      :max-width "100%"}}
        [:iframe.w3-border-0
         {:style {:position "absolute"
                  :top 0
                  :left 0
                  :width "100%"
                  :height "100%"}
          :title "Témoignage Rivalis"
          :allowfullscreen true
          :referrerpolicy "strict-origin-when-cross-origin"
          :allow
          "accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
          :src "https://www.youtube-nocookie.com/embed/2pdArs1er14?si=SIIgyRVyBtEEsETX"}]]
       [:div.w3-row
        [:h1 "Retrouvez nos avis, sur trustpilot"]
        [:div.w3-center
         [:iframe.w3-amber.w3-border-0
          {:title "Embedded Content"
           :src
           "https://www-rivalis-centre-fr.filesusr.com/html/4bddde_f97d5ae82fa0630dbf81980918522788.html"}]]]]
      [:div.w3-center (contact-button)])
     [:div#contact
      (rsection
       "Contactez-moi"
       "Rendez-vous pour un diagnostic gratuit"
       (cimg {} :medium (:anthony images))
       [:p.w3-content
        "Je viendrais dans votre commerce, atelier, dépôt ou bureau, et j'établirais un diagnostic gratuit - un rendez-vous de deux heures - à l'issue duquel vous déciderez si nous travaillerons ensemble."]
       [:div.w3-mobile
        [:form.w3-container.w3-flex {:style {:gap "1em"
                                             :flex-direction "column"}
                                     :onkeydown "return event.key != 'Enter';"
                                     :action "/contact"
                                     :method "post"}
         [:input.w3-input.w3-animate-input {:type "text"
                                            :style {:max-width "30em"
                                                    :width "100%"}
                                            :name "company"
                                            :autocomplete "organization"
                                            :placeholder "Société"}]
         [:input.w3-input.w3-animate-input {:type "text"
                                            :name "name"
                                            :style {:max-width "30em"
                                                    :width "100%"}
                                            :autocomplete "family-name"
                                            :placeholder "Nom"}]
         [:input.w3-input.w3-animate-input {:type "text"
                                            :style {:max-width "30em"
                                                    :width "100%"}
                                            :name "firstname"
                                            :autocomplete "given-name"
                                            :placeholder "Prénom"}]
         [:input.w3-input.w3-animate-input {:type "email"
                                            :style {:max-width "30em"
                                                    :width "100%"}
                                            :name "mail"
                                            :autocomplete "email"
                                            :placeholder "Mail"}]
         [:input.w3-input.w3-animate-input {:type "tel"
                                            :style {:max-width "30em"
                                                    :width "100%"}
                                            :name "phone"
                                            :autocomplete "tel"
                                            :pattern "^(?:0|\\+33)[0-9]{9}"
                                            :placeholder "Téléphone 0601010101"}]
         [:input.w3-input.w3-animate-input {:type "text"
                                            :style {:max-width "30em"
                                                    :display "none"
                                                    :width "100%"}
                                            :name "adress"
                                            :autocomplete "tel"}]
         [:input.w3-button.w3-orange.w3-ripple.w3-text-white.w3-large.w3-bold
          {:type "submit"
           :style {:max-width "30em"
                   :width "100%"}
           :onclick "document.getElementById(\"contact\").style.cursor = \"wait\";"
           :value "Envoyer"}]]])]]))

(def rivalis-map
  {:title {:en "The entrepreneur coaching offer"
           :fr "L'offre conseils au TPE"}
   :description {:en "The entrepreneur coaching offer"
                 :fr "L'offre conseils au TPE"}
   :handler rivalis-body})
