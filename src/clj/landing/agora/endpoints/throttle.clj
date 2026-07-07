(ns landing.agora.endpoints.throttle
  "Shared per-IP rate limiter for state-mutating authoring endpoints (KI + article
  create / edit / link / translate). Blunts spam and storage abuse before the
  Layer-2 confidence-budget quota exists. Separate from the auth credential
  limiter (login/register) so authoring and sign-in throttles don't share a budget."
  (:require
   [auto-web.middleware.rate-limit :refer [make-rate-limiter stop-rate-limit]]
   [mount.core                     :refer [defstate]]))

(defstate
 authoring-rate-limiter
 "Per-IP throttle on authoring writes — 30 actions / minute, then 429. Applied as
          method-level middleware on the mutating routes only (never on reads/search)."
 :start (make-rate-limiter {:limit 30
                            :window-ms 60000
                            :name "landing.agora.authoring"
                            :cleanup-interval-ms 60000})
 :stop (stop-rate-limit authoring-rate-limiter))
