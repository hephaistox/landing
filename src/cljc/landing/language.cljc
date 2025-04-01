(ns landing.language
  "Define language"
  (:require
   [auto-web.components.v-lang :as wvlang]))

(def possible-langs [:fr :en])

(def default-language :fr)

(defn lang-bar
  [http-request & opts]
  (apply wvlang/lang-bar possible-langs (:lang http-request) opts))
