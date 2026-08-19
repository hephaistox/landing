(ns landing.agora.text
  "Text normalization shared by clj and cljs. The platform gap only — the JVM and the browser fold
  accents through different APIs, and a divergence between them would be a bug (a slug or an alias
  key computed on one side must equal the one computed on the other)."
  (:require
   [clojure.string :as str]))

(defn fold-accents
  "`s` with its diacritics removed: `\"L'Être\"` → `\"L'Etre\"`. Decomposes to NFD, then drops the
  combining marks. nil-safe (yields \"\")."
  [s]
  #?(:clj (-> (java.text.Normalizer/normalize (or s "") java.text.Normalizer$Form/NFD)
              (str/replace #"\p{M}+" ""))
     :cljs (-> (.normalize (or s "") "NFD")
               (str/replace #"[\u0300-\u036f]" ""))))
