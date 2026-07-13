(ns landing.agora.frontend.fmt "Display-formatting helpers shared across Agora views.")

(defn utc
  "ISO-8601 UTC string (e.g. \"2026-07-03T00:00:00Z\") -> \"2026-07-03 00:00 UTC\",
  or nil when the input is not a usable timestamp."
  [iso]
  (when (and (string? iso) (>= (count iso) 16)) (str (subs iso 0 10) " " (subs iso 11 16) " UTC")))

(defn short-date
  "A compact date for cards from an ISO-8601 UTC string: `today-label` / `yesterday-label`
  for those UTC days, else `DD/MM` within the current year, else `DD/MM/YYYY`. The labels
  are passed in so this stays i18n-free. nil for an unusable timestamp."
  [iso today-label yesterday-label]
  (when (and (string? iso) (>= (count iso) 10))
    (let [y (js/parseInt (subs iso 0 4))
          m (js/parseInt (subs iso 5 7))
          d (js/parseInt (subs iso 8 10))]
      (when-not (or (js/isNaN y) (js/isNaN m) (js/isNaN d))
        (let [now (js/Date.)
              ty (.getUTCFullYear now)
              tm (inc (.getUTCMonth now))
              td (.getUTCDate now)
              yst (js/Date. (- (.getTime now) 86400000))
              yy (.getUTCFullYear yst)
              ym (inc (.getUTCMonth yst))
              yd (.getUTCDate yst)
              pad (fn [n] (if (< n 10) (str "0" n) (str n)))]
          (cond
            (and (= y ty) (= m tm) (= d td)) today-label
            (and (= y yy) (= m ym) (= d yd)) yesterday-label
            (= y ty) (str (pad d) "/" (pad m))
            :else (str (pad d) "/" (pad m) "/" y)))))))
