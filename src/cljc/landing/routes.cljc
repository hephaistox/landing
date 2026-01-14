(ns landing.routes
  "Defines the routes - all url internals and externals that are potential linked with the website itself.")

(def ^:private info-mail "Local factorization" "info@hephaistox.fr")

(def routes-dic
  {:disclaimer {:fr "Politique de confidentialité"
                :en "Disclaimer"}
   :rivalis {:fr "Conseil aux TPEs"
             :en "Small entreprise coaching"}
   :contacts {:fr "Contact"
              :en "Contact"}
   :projets {:fr "Projets informatique"
             :en "IT project"}
   :advanced-industry {:fr "Industrie"
                       :en "Industry"}
   :who-are-we {:fr "Qui nous sommes?"
                :en "Who are we?"}
   :about-this-website {:fr "A propos de ce site"
                        :en "About this website"}
   :home {:fr "Accueil"
          :en "Home"}
   :info-mail {:fr info-mail
               :en info-mail}
   :admin {:fr "Admin"
           :en "Admin"}
   :fb {:fr "FB"
        :en "FB"}
   :hephaistox {:fr "Hephaistox"
                :en "Hephaistox"}
   :legal-notice {:fr "Mentions légales"
                  :en "Legal notice"}
   :privacy {:fr "Clause de non responsabilité"
             :en "Privacy"}})

(def links
  (->>
    [{:url "/"
      :alt "Home link"
      :text :home
      :link-id :home}
     {:link-id :who-are-we
      :text :who-are-we
      :url "/articles/who-are-we"}
     {:link-id :projets
      :text :projets
      :url "/articles/projets"}
     {:link-id :hephaistox
      :text :hephaistox
      :url "/articles/hephaistox"}
     {:link-id :rivalis
      :text :rivalis
      :url "/articles/rivalis"}
     {:link-id :legal-notice
      :text :legal-notice
      :url "/articles/legal-notice"}
     {:url "/articles/contacts"
      :text :contacts
      :link-id :contacts}
     {:url "/articles/about-site"
      :text :about-this-website
      :link-id :about-this-website}
     {:url "/articles/privacy"
      :text :privacy
      :link-id :privacy}
     {:url "/articles/disclaimer"
      :text :disclaimer
      :link-id :disclaimer}
     {:url "/articles/contact-validated"
      :text :projets
      :link-id :contact-validated}
     {:url "/articles/projets"
      :text :projets
      :link-id :projets}
     {:url "/all-kind-of-checks"
      :text :admin
      :link-id :admin}
     {:url "/articles/advanced-solution-for-industries"
      :text :advanced-industry
      :link-id :advanced-industry}
     {:url "/articles/hephaistox"
      :text :hephaistox
      :link-id :hephaistox}
     {:url "https://github.com/hephaistox"
      :text :github
      :link-id :github}
     {:url "https://www.facebook.com/profile.php?id=61586135248424"
      :text :fb
      :link-id :fb}
     {:url "mailto:anthony@hephaistox.fr"
      :link-id :mail-postmaster-caumond-fr}
     {:url "mailto:management@caumond.fr"
      :link-id :mail-management-caumond-fr}
     {:url "mailto:postmaster@caumond.fr"
      :link-id :mail-postmaster-caumond-fr}
     {:url "mailto:abuse@caumond.fr"
      :link-id :mail-abuse-caumond-fr}
     {:url "mailto:management@caumond.com"
      :link-id :mail-management-caumond.com}
     {:url "mailto:postmaster@caumond.com"
      :link-id :mail-postmaster-caumond.com}
     {:url "mailto:abuse@caumond.com"
      :link-id :mail-abuse-caumond.com}
     {:url "mailto:anthony@hephaistox.fr"
      :link-id :mail-anthony-hephaistox-fr}
     {:url "mailto:mati@hephaistox.fr"
      :link-id :mail-mati-hephaistox-fr}
     {:url "mailto:abuse@hephaistox.fr"
      :link-id :mail-abuse-hephaistox-fr}
     {:url "mailto:info@hephaistox.fr"
      :link-id :mail-info-hephaistox-fr}
     {:url "mailto:postmaster@hephaistox.fr"
      :link-id :mail-postmaster-com}
     {:url (str "mailto:" info-mail)
      :skip-test? true
      :text :info-mail
      :link-id :info-mail}
     {:url "https://www.youtube.com/@HephaistoxSC"
      :link-id :youtube}
     {:url "https://www.linkedin.com/in/mateuszmazurczak/"
      :skip-test? true
      :link-id :linkedin-mati}
     {:url "https://www.linkedin.com/in/anthony-caumond-a365b15/"
      :skip-test? true
      :link-id :linkedin-anthony}
     {:url "https://www.linkedin.com/company/hephaistox"
      :skip-test? true
      :link-id :linkedin}
     {:url "https://hephaistox.fr"
      :link-id :production-fr}]
    (mapv (fn [link] [(:link-id link) link]))
    (into {})))

(def images
  (->> [{:url "/images/logos/hephaistox_logo.png"
         :alt "Logo hephaistox"
         :img-id :hephaistox-logo}]
       (mapv (fn [link] [(:img-id link) link]))
       (into {})))

(def social
  (->> [{:fa-icon "fa-linkedin fa-brands"
         :link :linkedin
         :social-id :linkedin
         :label "Linkedin"}
        {:fa-icon "fa-envelope-open"
         :link :info-mail
         :social-id :mail
         :label "Mail"}
        {:fa-icon "fa-brands fa-facebook-f"
         :link :fb
         :social-id :fb
         :label "Facebook"}
        {:fa-icon "fa-github fa-brands"
         :link :github
         :social-id :github
         :label "Github"}
        {:fa-icon "fa-youtube fa-brands"
         :social-id :youtube
         :link :youtube
         :label "Youtube"}]
       (mapv (fn [item] [(:social-id item)
                         (-> item
                             (update :link links))]))
       (into {})))

(defn url-txt
  [link-id]
  (let [{:keys [url]} (get links link-id)
        link (some-> link-id
                     name)]
    [:a {:href url
         "data-link-name" link}
     url]))

(defn mail-txt
  [link-id l]
  (let [{:keys [link-id url]} (get links link-id)
        link (some-> link-id
                     name)
        mail (-> (get routes-dic link-id)
                 (get l))]
    [:a {:href url
         "data-link-name" link}
     mail]))

