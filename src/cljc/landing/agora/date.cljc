(ns landing.agora.date
  "Shared date display for Agora cards/views (clj + cljs). A compact date reads as the relative label
  when it is today/yesterday, else `DD/MM` within the current year (or `DD/MM/YYYY`). The i18n labels
  and connector are passed in, so this stays language-agnostic. Only the 'now' lookup is platform-
  specific (reader conditional); the rest is shared so the server and client never phrase a date
  differently.")

(defn- now-ymd
  "Today's and yesterday's UTC dates, each as a `[year month day]` vector."
  []
  #?(:clj (let [today (java.time.LocalDate/now java.time.ZoneOffset/UTC)
                yst (.minusDays today 1)]
            [[(.getYear today) (.getMonthValue today) (.getDayOfMonth today)]
             [(.getYear yst) (.getMonthValue yst) (.getDayOfMonth yst)]])
     :cljs (let [now (js/Date.)
                 yst (js/Date. (- (.getTime now) 86400000))]
             [[(.getUTCFullYear now) (inc (.getUTCMonth now)) (.getUTCDate now)]
              [(.getUTCFullYear yst) (inc (.getUTCMonth yst)) (.getUTCDate yst)]])))

(defn- pad [n] (if (< n 10) (str "0" n) (str n)))

(defn short-date
  "A compact date from an ISO-8601 UTC string: `today-label` / `yesterday-label` for those UTC days,
  else `DD/MM` within the current year, else `DD/MM/YYYY`. The labels are passed in so this stays
  i18n-free. nil for an unusable timestamp."
  [iso today-label yesterday-label]
  (when (and (string? iso) (>= (count iso) 10))
    (let [y (parse-long (subs iso 0 4))
          m (parse-long (subs iso 5 7))
          d (parse-long (subs iso 8 10))]
      (when (and y m d)
        (let [[today yst] (now-ymd)]
          (cond
            (= [y m d] today) today-label
            (= [y m d] yst) yesterday-label
            (= y (first today)) (str (pad d) "/" (pad m))
            :else (str (pad d) "/" (pad m) "/" y)))))))

(defn labelled-date
  "A card date phrase reading correctly in prose: the relative label (today/yesterday) **as-is**, else
  the `on` connector + the compact date — so it renders « Aujourd'hui » but « le 03/08 », never « le
  Aujourd'hui ». `labels` is `{:today :yesterday :on}`. nil for an unusable timestamp."
  [iso {:keys [today yesterday on]}]
  (when-let [s (short-date iso today yesterday)]
    (if (contains? #{today yesterday} s) s (str on " " s))))
