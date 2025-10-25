(ns landing.endpoints.html
  "Helpers for html endpoint"
  (:require
   [auto-web.middleware             :refer [wrap-add-language wrap-exception-handling]]
   [env                             :refer [env-dep-middlewares]]
   [landing.endpoints.html.error-be :refer [exception-response]]
   [landing.language                :refer [default-language]]
   [ring.middleware.cookies         :as ring-cookies]
   [ring.middleware.gzip            :as ring-gzip]
   [ring.middleware.x-headers       :refer [wrap-frame-options]]))

(def html-middlewares
  "Middlewares to display an html endpoint.
  Cookies are added, language is managed and the strategy stored on the client. The frames can't inject, files are zipped if big enough.

  If in dev environment, reload is added.
  Exceptions are caught and `exception-response` is displayed instead."
  [ring-cookies/wrap-cookies
   [wrap-frame-options :deny]
   ring-gzip/wrap-gzip
   env-dep-middlewares
   [wrap-add-language default-language]
   [wrap-exception-handling exception-response]])


