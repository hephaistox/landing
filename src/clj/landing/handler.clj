(ns landing.handler
  "Handler turns an http http-request into a response"
  (:require
   [landing.endpoints.check-url       :refer [check-url-route]]
   [landing.endpoints.contact         :refer [contact-route]]
   [landing.endpoints.default-handler :refer [default-handler]]
   [landing.endpoints.exception       :refer [exception-route]]
   [landing.endpoints.html.admin-be   :refer [admin-route]]
   [landing.endpoints.html.article-be :refer [article-route]]
   [landing.endpoints.html.home-be    :refer [home-route]]
   [landing.endpoints.ping            :refer [ping-route]]
   [landing.endpoints.plus            :refer [plus]]
   [landing.endpoints.resource        :refer [resource-handler]]
   [landing.endpoints.swagger         :refer [api-ep]]
   [reitit.ring                       :as rring]))

(defn router
  []
  (rring/router [(ping-route "/ping")
                 (exception-route "/exception")
                 (home-route "/")
                 (plus "/plus")
                 (article-route "/articles")
                 (admin-route "/all-kind-of-checks")
                 (contact-route "/contact")
                 (check-url-route "/check-url")
                 (api-ep "/api")]
                {}))

(defn handler [] (rring/ring-handler (router) (rring/routes resource-handler default-handler) {}))
