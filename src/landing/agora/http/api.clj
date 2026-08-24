(ns landing.agora.http.api
  "Agora JSON API middleware stack

  The route data announcing it, the error body schema, and the caller's id."
  (:require
   [landing.agora.http.error          :as error]
   [muuntaja.core                     :as m]
   [reitit.coercion.malli             :refer [coercion]]
   [reitit.ring.coercion              :as rcoercion]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(def mw
  "The middleware stack of every JSON API mount.

  In reitit's order: query/form params, content, negotiation, response encoding, exception handling,
  request decoding, request coercion.

  `error/exception-middleware` is what makes a failure inside a mount come back as a negotiated JSON
  body rather than the branded HTML 500 the outer web handler serves — and what maps a database
  outage to a 503 the client can retry, instead of an opaque 500."
  [parameters/parameters-middleware
   muuntaja/format-negotiate-middleware
   muuntaja/format-response-middleware
   error/exception-middleware
   muuntaja/format-request-middleware
   rcoercion/coerce-request-middleware])

(def error-body
  "The shape of every error response: `{:error \"...\"}`. Open — some carry extra keys, e.g. the
  offending `:id`, or publish's `:documents`."
  [:map [:error :string]])

(defn route-data
  "Route data for a JSON API mount under `tag`: malli coercion, JSON encoding, `mw`, and the 500
  every route may answer. `extra` is merged over it, its `:responses` merged into the shared ones,
  for a mount that adds its own (`admin` its owner guard's 401/403 and `:no-doc`)."
  ([tag] (route-data tag nil))
  ([tag extra]
   (-> {:coercion coercion
        :middleware mw
        :muuntaja m/instance
        :responses {500 {:description "Unexpected server error"}}
        :swagger {:tags #{tag}}}
       (merge (dissoc extra :responses))
       (update :responses merge (:responses extra)))))

(defn uid
  "The caller's user id, or nil when anonymous. The session is the outer `wrap-session` signed
  cookie."
  [req]
  (get-in req [:session :user-id]))
