(ns landing.language
  "Language constants and request-language detection.

  `default-language` (a keyword) is consumed by the `wrap-add-language`
  middleware (auto-web). The string-valued helpers (`default-lang`,
  `supported-langs`, `pick-lang`, …) are used by handlers that build
  URL paths or pick a static file under `resources/public/<lang>/`."
  (:require
   [clojure.string :as str]))

(def default-language :fr)

(def supported-langs #{"fr" "en"})

(def default-lang "fr")

(defn- header
  "Case-insensitive lookup of a Ring header."
  [req header-name]
  (some (fn [[k v]] (when (= header-name (str/lower-case (name k))) v))
        (:headers req)))

(defn cookie-lang
  "Return the `lang` cookie value if present and recognized. Accepts both the
  current `lang=fr` form and the legacy `lang=:fr` / `lang=%3Afr` forms."
  [req]
  (some-> (header req "cookie")
          (->> (re-find #"(?i)\blang=(?:%3A|:)?(en|fr)\b"))
          second
          str/lower-case))

(defn accept-lang
  "Pick the first supported language from `Accept-Language`."
  [req]
  (some->> (header req "accept-language")
           (re-seq #"(?i)\b(en|fr)\b")
           first
           second
           str/lower-case))

(defn pick-lang
  "Resolve a request's language: cookie → Accept-Language → `default-lang`."
  [req]
  (or (cookie-lang req) (accept-lang req) default-lang))
