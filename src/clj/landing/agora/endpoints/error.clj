(ns landing.agora.endpoints.error
  "Shared reitit exception handling for the Agora JSON API.

  A database failure — the `:landing.agora.db.document/db-unavailable` ex-info, or a raw
  `java.sql.SQLException` — is surfaced as a 503 (the service is momentarily unavailable, the client
  should retry) instead of an opaque 500. Every other exception keeps reitit's default handling."
  (:require
   [reitit.ring.middleware.exception :as exception]))

(def ^:private unavailable
  {:status 503
   :body {:error "service temporarily unavailable, please retry"}})

(def exception-middleware
  "Drop-in replacement for reitit's default exception middleware that maps DB
  failures to 503."
  (exception/create-exception-middleware
   (merge exception/default-handlers
          {:landing.agora.db.document/db-unavailable (fn [_e _req] unavailable)
           java.sql.SQLException (fn [_e _req] unavailable)})))
