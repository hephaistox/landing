(ns landing.agora.util
  "Small shared helpers for Agora read models."
  (:import (java.time LocalDateTime ZoneOffset)))

(defn ->utc-iso
  "Render a stored (UTC-convention) DATETIME as an ISO-8601 UTC string, e.g.
  \"2026-07-02T00:00:00Z\". Also makes the value JSON-serializable."
  [^LocalDateTime ldt]
  (some-> ldt
          (.atOffset ZoneOffset/UTC)
          .toInstant
          .toString))
