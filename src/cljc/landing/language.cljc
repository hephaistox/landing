(ns landing.language
  "Define language"
  (:require
   [auto-web.components.lang :refer [clang-bar]]))

(def possible-langs [:fr :en])

(def default-language :fr)

(defn landing-lang-bar
  "A lang bar present all possible operations, underline the one currently selected and is clickable to modify it."
  [opts http-request]
  (clang-bar opts (:lang http-request) possible-langs nil))
