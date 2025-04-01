(ns landing.routes
  "Defines the routes - all url internals and externals that are potential linked with the website itself.")

;; (def port 8080)

;; (def base-site
;;   "Where the website is deployed"
;;   (or "https://app-5aef6596-0f35-4ccd-a3f7-02c33c2a1864.cleverapps.io/"
;;       "https://www.hephaistox.com"
;;       (str "http://localhost:" port)))

;; (defn add-base
;;   "Turns `url` into an absolute `url`.

;;   `url` starting with http are skipped, as they are considered as external ones."
;;   [url]
;;   (if (or (str/starts-with? url "http") (str/starts-with? url "mailto"))
;;     url
;;     (str base-site "/" url)))

(def dic
  {:disclaimer {:fr "Politique de confidentialité"
                :en "Disclaimer"}
   :contact {:fr "Contact"
             :en "Contact"}
   :simulation {:fr "Simulation"
                :en "Simulation"}
   :home {:fr "Accueil"
          :en "Home"}
   :privacy {:fr "Clause de non responsabilité"
             :en "Privacy"}})

(def links
  (->>
    [{:url "/"
      :alt "Home link"
      :text :home
      :name :home}
     {:url "/articles/contact"
      :text :contact
      :name :contact}
     {:url "/articles/privacy"
      :text :privacy
      :name :privacy}
     {:url "/articles/disclaimer"
      :text :disclaimer
      :name :disclaimer}
     {:url "/articles/simulation"
      :text :simulation
      :name :simulation}
     {:name :mail
      :text :mail
      :url
      "https://outlook.office365.com/owa/calendar/MatiAnthony@hephaistox.com/bookings/s/jCJNJ2lYPUmwF_13sgEmng2"}
     {:url "https://github.com/hephaistox"
      :text :github
      :name :github}
     {:url "mailto:anthony@hephaistox.com"
      :skip-test? true
      :text :info-mail
      :name :info-mail}
     {:url "https://www.youtube.com/@HephaistoxSC"
      :name :youtube}
     {:url "https://www.linkedin.com/in/mateuszmazurczak/"
      :skip-test? true
      :name :linkedin-mati}
     {:url "https://www.linkedin.com/in/anthony-caumond-a365b15/"
      :skip-test? true
      :name :linkedin-anthony}
     {:url "https://www.linkedin.com/company/hephaistox"
      :skip-test? true
      :name :linkedin}]
    (mapv (fn [link] [(:name link) link]))
    (into {})))

(def images
  (->> [{:url "/images/logos/hephaistox_logo.png"
         :alt "Logo hephaistox"
         :name :hephaistox-logo}]
       (mapv (fn [link] [(:name link) link]))
       (into {})))

(def social
  (->> [{:fa-icon "fa-linkedin fa-brands"
         :link :linkedin
         :name :linkedin
         :label "Linkedin"}
        {:skip-test? true
         :link :mail
         :desc "To book a meeting with office"
         :target "blank"
         :name :meeting}
        {:fa-icon "fa-envelope-open"
         :link :info-mail
         :name :mail
         :label "Mail"}
        {:fa-icon "fa-github fa-brands"
         :link :github
         :name :github
         :label "Github"}
        {:fa-icon "fa-youtube fa-brands"
         :name :youtube
         :link :youtube
         :label "Youtube"}]
       (mapv (fn [item] [(:name item)
                         (-> item
                             (update :link links))]))
       (into {})))
