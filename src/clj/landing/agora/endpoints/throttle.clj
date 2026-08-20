(ns landing.agora.endpoints.throttle
  "Shared per-IP rate limiters for the Agora surface, applied as method-level middleware on the
  specific routes that need them (never on reads/search). Two separate budgets so sign-in and
  authoring throttles don't share a counter:
   - `authoring-rate-limiter` — state-mutating writes (document create/edit/delete, publication
     create/rename/publish/delete, create-person, alias rename);
   - `credential-rate-limiter` — the unauthenticated credential endpoints (login/register), the
     brute-force / credential-stuffing surface."
  (:require
   [auto-web.middleware.rate-limit :refer [make-rate-limiter stop-rate-limit]]
   [mount.core                     :refer [defstate]]))

(defstate authoring-rate-limiter
          "Per-IP throttle on authoring writes — 30 actions / minute, then 429."
          :start (make-rate-limiter {:limit 30
                                     :window-ms 60000
                                     :name "landing.agora.authoring"
                                     :cleanup-interval-ms 60000})
          :stop (stop-rate-limit authoring-rate-limiter))

(defstate
 credential-rate-limiter
 "Per-IP throttle on login/register — 10 attempts / minute, then 429. Blunts credential
          stuffing and scripted account creation."
 :start (make-rate-limiter {:limit 10
                            :window-ms 60000
                            :name "landing.agora.credential"
                            :cleanup-interval-ms 60000})
 :stop (stop-rate-limit credential-rate-limiter))
