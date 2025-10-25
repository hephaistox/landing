(ns landing.endpoints.default-handler
  "Handler when route is not found, page not acceptable or method disallowed."
  (:require
   [auto-web.middleware             :refer [wrap-add-language]]
   [landing.endpoints.html.error-be :refer [page-not-found-response]]
   [landing.language                :refer [default-language]]
   [reitit.ring                     :as rring]
   [ring.middleware.cookies         :as ring-cookies]
   [ring.middleware.gzip            :as ring-gzip]))

(def default-handler
  (-> (rring/create-default-handler {:not-found page-not-found-response
                                     :not-acceptable page-not-found-response
                                     :method-not-allowed page-not-found-response})
      (wrap-add-language default-language)
      ring-cookies/wrap-cookies
      ring-gzip/wrap-gzip))
