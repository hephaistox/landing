(ns landing.agora.frontend.fmt "Display-formatting helpers shared across Agora views.")

(defn utc
  "ISO-8601 UTC string (e.g. \"2026-07-03T00:00:00Z\") -> \"2026-07-03 00:00 UTC\",
  or nil when the input is not a usable timestamp."
  [iso]
  (when (and (string? iso) (>= (count iso) 16)) (str (subs iso 0 10) " " (subs iso 11 16) " UTC")))
