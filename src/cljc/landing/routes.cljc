(ns landing.routes
  "Defines the routes - all url internals and externals that are potential linked with the website itself.")

(def dic
  {:disclaimer {:fr "Politique de confidentialité"
                :en "Disclaimer"}
   :contacts {:fr "Contact"
              :en "Contact"}
   :projets {:fr "Projet"
             :en "Projet"}
   :digital-twin {:fr "Jumeau numérique"
                  :en "Digital twin"}
   :demo {:fr "Démonstrateur"
          :en "Demo"}
   :who-are-we {:fr "Qui nous sommes?"
                :en "Who are we?"}
   :about-this-website {:fr "A propos de ce site"
                        :en "About this website"}
   :home {:fr "Accueil"
          :en "Home"}
   :admin {:fr "Admin"
           :en "Admin"}
   :hephaistox {:fr "Hephaistox"
                :en "Hephaistox"}
   :sasu-caumond {:fr "SASU caumond"
                  :en "SASU caumond"}
   :js-example {:fr "Example de jobshop"
                :en "Jobshop example"}
   :privacy {:fr "Clause de non responsabilité"
             :en "Privacy"}})

(def links
  (->>
    [{:url "/"
      :alt "Home link"
      :text :home
      :link-id :home}
     {:link-id :demo
      :text :demo
      :url "/articles/js-example"}
     {:link-id :who-are-we
      :text :who-are-we
      :url "/articles/who-are-we"}
     {:link-id :projets
      :text :projets
      :url "/articles/projets"}
     {:link-id :hephaistox
      :text :hephaistox
      :url "/articles/hephaistox"}
     {:link-id :sasu-caumond
      :text :sasu-caumond
      :url "/articles/sasu-caumond"}
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
     {:url "/articles/projets"
      :text :projets
      :link-id :projets}
     {:url "/all-kind-of-checks"
      :text :admin
      :link-id :admin}
     {:url "/articles/digital-twin"
      :text :digital-twin
      :link-id :digital-twin}
     {:url "/articles/hephaistox"
      :text :hephaistox
      :link-id :hephaistox}
     {:url "/js-example"
      :text :js-example
      :link-id :js-example}
     {:link-id :mail
      :text :mail
      :url
      "https://outlook.office365.com/owa/calendar/MatiAnthony@hephaistox.com/bookings/s/jCJNJ2lYPUmwF_13sgEmng2"}
     {:url "https://github.com/hephaistox"
      :text :github
      :link-id :github}
     {:url "mailto:anthony@hephaistox.com"
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
      :link-id :linkedin}]
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
        {:skip-test? true
         :link :mail
         :desc "To book a meeting with microsoft office"
         :target "blank"
         :social-id :meeting}
        {:fa-icon "fa-envelope-open"
         :link :info-mail
         :social-id :mail
         :label "Mail"}
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
