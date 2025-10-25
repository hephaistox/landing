(ns landing.endpoints.contact
  "Stores data from contacts"
  (:require
   [auto-web.middleware.rate-limit    :refer [make-rate-limiter stop-rate-limit]]
   [landing.db                        :refer [ds execute-query query]]
   [landing.routes                    :refer [links]]
   [mount.core                        :refer [defstate]]
   [muuntaja.core                     :as m]
   [postal.core                       :as postal]
   [reitit.coercion.malli             :refer [coercion]]
   [reitit.ring.coercion              :as rcoercion]
   [reitit.ring.middleware.exception  :as exception]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [ring.middleware.keyword-params    :refer [wrap-keyword-params]]
   [ring.util.response                :as response]
   [safely.core                       :refer [safely]]))

(defstate rate-limiter
          :start (make-rate-limiter {:limit 2
                                     :window-ms 6000
                                     :name "landing.endpoints.contact"
                                     :cleanup-interval-ms 60000})
          :stop (stop-rate-limit rate-limiter))

(def gmail-config
  {:host "smtp.gmail.com"
   :port 587
   :user "caumond@gmail.com"
   :pass (System/getenv "GMAIL_APP_PASSWORD")
   :tls true})              ;; Gmail requires TLS

(defn send-email
  [body]
  (safely (postal/send-message gmail-config
                               {:from "caumond@gmail.com"
                                :to "anthony@caumond.fr"
                                :subject "New contact"
                                :body body})
          :on-error
          :failed? #(not= 0 (:code %))
          :max-retries 5
          :retry-delay [:random-exp-backoff :base 3000 :+/- 0.50]))

(comment
  (send-email "Test5")
  ;
)

(defn handle-contact-handler
  [req]
  (let [{:keys [company name firstname mail phone adress]} (:params req)]
    (if (= "" adress)
      (do (future
           (send-email
            (format
             "Nouveau contact\nEntreprise: %s: \nName: %s\nFirstname: %s\nMail: %s\nPhone: %s"
             company
             name
             firstname
             mail
             phone)))
          (safely (->> (query company name firstname mail phone)
                       (execute-query ds))
                  :on-error
                  :max-retries 5
                  :retry-delay [:random-exp-backoff :base 3000 :+/- 0.50])
          (response/redirect (-> :contact-validated
                                 links
                                 :url)))
      (response/bad-request "unexpected"))))

(defn contact-route
  "A post on this endpoint stores company, name firstname, mail and phone"
  [prefix]
  [prefix
   {:post {:coercion coercion
           :description "Use by the contact form"
           :handler handle-contact-handler
           :middleware [(:middleware-fn rate-limiter)
                        ;; content-negotiation
                        parameters/parameters-middleware
                        muuntaja/format-negotiate-middleware
                        ;; encoding response body
                        muuntaja/format-response-middleware
                        ;; exception handling
                        exception/exception-middleware
                        ;; decoding request body
                        muuntaja/format-request-middleware
                        rcoercion/coerce-response-middleware
                        rcoercion/coerce-request-middleware
                        wrap-keyword-params]
           :muuntaja m/instance
           :operationId "contactForm"
           :parameters {:form [:map
                               [:company :string]
                               [:name :string]
                               [:firstname :string]
                               [:mail :string]
                               [:phone :string]
                               [:adress [:maybe :string]]]}
           :summary "Post contact data"
           :swagger {:tags #{:public-website}}}}])

