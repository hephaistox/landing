(ns landing.article.privacy)

(defn privacy-body
  [_http-request l]
  (case l
    :en
    [:article.text
     [:h1 "Privacy policy"]
     [:p
      "This \"Privacy Statement\" regulates the processing of your personal data by Hephaistox, a company with registered in France with the office registered at 5 rue saint Dominique, 63350 JOZE with legal name \"SASU CAUMOND\". Hephaistox is our brand name and identity thus in the documents hereinafter will be reffered as \"Hephaistox\". Please read this Privacy Statement carefully, because it contains essential information about how your personal data are processed and which cookies are used. By using our services or delivering yours, signing an agreement with us or visiting our website (accessible via the following address: https://www.hephaistox.com), you declare that you have read this Privacy Statement and that you expressly agree to its content as well as to the processing itself."]
     [:h2 "1. GDPR"]
     [:p
      "Hephaistox is compliant with the European Regulation 2016/679 of 27 April 2016 on the protection of personal data (hereinafter “GDPR”) and the implementations of the Directive in local legislation. When using this website, the person responsible for the processing of your personal data can be reached at in the office 5 rue Saint Dominique, 63350 JOZE or via email at: info@hephaistox.com"]
     [:h2 "2. Personal data"]
     [:h3 "2.1. Personal data you communicate to us"]
     [:p "Without registration and when visiting our website: we don't track any information."]
     [:p
      "When you contact us as a client, contractor, supplier, prospect or other external party and when you decided to provide the following information to us, directly or via a third party in any printed, written or electronic form: your company name, addresses, VAT number and registration number, your name(s), surname, e-mail address, log in ID, log in password (encrypted), log in and user activity, function, office phone number, cell phone number, picture, IP address, title, comments field (free text field), gender, language."]
     [:p "When you subscribe to our newsletter: your company name, name(s) and e-mail address"]
     [:h3 "2.2. Ways we collect data from you"]
     [:p
      "Through the use of cookies on our website and when you provide personal data directly to us in any way or through your employer or colleagues."]
     [:h2 "3. Cookies"]
     [:p
      "Hephaistox use only necessary cookies for the website to function properly, without which the site would not work. This type of cookie does not collect any personally identifiable information about you and does not track your browsing habits. For example, cookies are used to remember your language preferences."]
     [:h2 "4. Purposes of processing"]
     [:p "Hephaistox will only use the Personal Data collected by you for the following purposes:"]
     [:h3 "4.1. Category 1"]
     [:p
      "The maintenance and improvement of our website and the inclusion of Personal Data in anonymous statistics, from which the identity of specific persons or companies cannot be determined, based on the legitimate interests of Hephaistox to continuously improve its website and services."]
     [:h3 "4.2. Category 2"]
     [:p
      "Managing our contracts and contacts with you, providing our services, giving information, including marketing information, about our current and future services and ensuring follow-up including activity tracking."]
     [:h2 "5. Information protection"]
     [:p
      "Hephaistox takes precautions to preserve your personal data, however any data communication via the internet cannot be guaranteed to be 100% secure. Hephaistox cannot guarantee or warrant the security of any information you transmit to us or receive from us."]
     [:p
      "You are not obliged to provide us with your Personal Data, but you must understand that the provision of certain services becomes impossible if you refuse the processing."]
     [:ul
      [:li "External services"]
      [:li "Mailchimp - we use mailchimp for storing information from newsletter."]
      [:li "Outlook - we use outlook for storing information about booked online meetings."]
      [:li "Youtube - we use youtube for storing our videos and possible comments you write."]
      [:li "CleverCloud - we use cc for infrastracture and hosting the data"]
      [:li
       "Github - we use github for storing the technical information and source code of applications"]]
     [:p
      "These third-party services are outside of our control. Providers may, at any time, change their terms of service, purpose, and use of cookies, etc."]
     [:h2 "Further questions"]
     [:p
      "If you have any further questions or comments regarding the processing of your Personal Data, please contact us, either by e-mail to info@hephaistox.com or by post to Hephaistox (address indicated above)"]]
    :fr
    [:article.text
     [:h1 "Politique de confidentialité"]
     [:p
      "Cette \"déclaration de confidentialité\" régit le processus de gestion des données personnelles de Hephaistox, une entreprise enregistrée en France à l'adresse 5 rue saint Dominique, 63350 JOZE dont la dénomination légale est \"SASU CAUMOND\". Hephaistox est le nom de notre marque et notre identité, c'est pourquoi nous ferons référence à \"Hephaistox\" dans les documents. Merci de lire cette déclaration de confidentialité avec attention, car elle contient des informations essentielles sur la façon par laquelle vos données personnelles sont traitées et comment les cookies sont utilisés. En utilisant nos services ou en délivrant les vôtres, en signant un contrat avec nous ou en visitant notre site Internet (accessible via https://hephaistox.com), vous déclarez avoir lu cette déclaration de confidentialité and vous acceptez expressément son contenu tout comme la gestion elle-même."]
     [:h2 "1. RGPD"]
     [:p
      "Hephaistox respecte la directive Européenne 2016/679 du 27 Avril 2016 sur la protection des données personnelles (nommée ci-après \"RGPD\") ainsi que l'implémentation de cette directive dans les législations locales. Pour l'utilisation de ce site Internet, la personne responsable du traitement de vos données personnelles peut être contactée au bureau 5 rue Saint Dominique, 63350 JOZE ou par email à: info@hephaistox.com"]
     [:h2 "2. Données personnelles"]
     [:h3 "2.1. Les données personnelles que vous nous communiquez"]
     [:p
      "Si vous ne vous êtes pas enregistré et que vous visitez notre site: nous ne suivons aucune information."]
     [:p
      "Lorsque vous nous contactez en tant que client, entrepreneur, fournisseur, prospect ou autre partie externe et que vous décidez de nous fournir les informations suivantes, directement ou par l'intermédiaire d'un tiers, sous une forme imprimée, écrite ou électronique : le nom et l'adresse de votre entreprise, votre numéro de TVA et votre numéro d'enregistrement, vos nom et prénom, votre adresse électronique, votre identifiant de connexion, votre mot de passe de connexion (crypté), votre activité de connexion et d'utilisateur, votre fonction, votre numéro de téléphone au bureau, votre numéro de téléphone portable, votre photo, votre adresse IP, votre titre, votre champ de commentaires (champ de texte libre), votre sexe, votre langue."]
     [:p
      "Lorsque vous vous inscrivez à notre lettre d'information : votre nom d'entreprise, votre (vos) nom(s) et votre e-mail."]
     [:h3 "2.2. Comment nous collectons les données"]
     [:p
      "A travers l'utilisation de cookies sur notre site Internet et lorsque vous saisissez directement des informations personnelles quelle que soit la manière par laquelle cela a été réalisé, même à travers votre employeur ou vos collègues"]
     [:h2 "3. Cookies"]
     [:p
      "Hephaistox utilise seulement les cookies nécessaires au bon fonctionnement du site Internet, sans cela le site ne fonctionnerait pas. Ce type de cookie ne contient aucune donnée personnelle et ne suit pas vos habitudes de navigation. Par exemple, les cookies sont utilisés pour conserver vos préférences de langues"]
     [:h2 "4. Finalités de traitement"]
     [:p "Hephaistox utilise seulement les informations personnelles pour les usages suivants:"]
     [:h3 "4.1. Catégorie 1"]
     [:p
      "La maintenance et l'amélioration de notre site Internet et l'inclusion de données personnelles pour des statistiques anonymes, pour laquelle l'identité de personnes précise ne peut être déterminée. Ces statistiques sont basées sur les intérêts légitimes d'Hephaistox d'améliorer continuellement son site Internet et ses services;"]
     [:h3 "4.2. Catégorie 2"]
     [:p
      "Pour gérer nos contrats et contacts avec vous, fournir nos services, vous fournir des informations, incluant des informations marketing à propos de nos services courants et futurs et assurer le suivi de nos activités."]
     [:h2 "5. Protection de l'information"]
     [:p
      "Hephaistox prends des précautions pour préserver des données personnelles, cependant aucune communication de données via Internet n'est sûre à 100%. Hephaistox ne peut garantir ou assurer la sécurité des informations que vous nous transmettez ou que nous recevons de votre part."]
     [:p
      "Vous n'êtes pas tenu de nous fournir vos données personnelles, mais vous devez comprendre que la fourniture de certains services deviennent impossible si vous refusez ce traitement de données personnelles."]
     [:h3 "Services externes"]
     [:ul
      [:li
       "Mailchimp - Nous utilisons mailchimp pour stocker les informations pour la gestion des lettres d'information."]
      [:li
       "Outlook - nous utilisons outlook pour stocker les informations à propos des rendez-vous en ligne."]
      [:li
       "Youtube - nous utilisons youtube pour stocker nos vidéos et les éventuels commentaires que vous y laissez."]
      [:li "CleverCloud - nous utilisons cc pour l'infrastructure et l'hébergement des données."]
      [:li
       "Github - nous utilisons github pour stocker les informations techniques et le code source des applications"]]
     [:p
      "Ces services tiers sont en dehors de notre contrôle. Ces fournisseurs peuvent, à tout moment, changer leur termes d'utilisation, but et utilisation de cookies, etc..."]
     [:h2 "Autres questions"]
     [:p
      "Si vous avez d'autres questions ou des commentaires sur notre gestion des données personnelles, merci de nous contacter, soit par e-mail à info@hephaistox.com ou par courrier à Hephaistox (cf. adresse ci-dessus)"]]))

(def privacy-map
  {:title {:en "Privacy"
           :fr "Politique de confidentialité"}
   :description {:en "Privacy"
                 :fr "Politique de confidentialité"}
   :handler privacy-body})
