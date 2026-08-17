(ns landing.language
  "Canonical language / locale metadata + request-language detection, shared by the
  backend and both frontends (cljc). Everything about *which* languages exist, their
  order, human labels and OpenGraph locales is defined here **once** — add a language
  to `languages` and routing, the switcher, SEO and detection all follow. UI string
  translations (the Agora dictionary) stay in landing.agora.frontend.i18n; this
  namespace owns language identity/metadata, not the translated copy.

  `default-language` (a keyword) is consumed by the `wrap-add-language` middleware
  (auto-web). The string helpers (`default-lang`, `supported-langs`, `pick-lang`, …)
  are used by handlers that build URL paths or pick a static file under
  `resources/public/<lang>/`."
  (:require
   [clojure.string :as str]))

(def languages
  "Supported language codes (ISO 639-1), in display order. The first is the default."
  ["fr" "en"])

(def default-lang "Default language code (string)." (first languages))

(def default-language
  "Default language as a keyword (for wrap-add-language)."
  (keyword default-lang))

(def supported-langs "Supported codes as a set, for membership tests." (set languages))

(def language-name
  "Human-readable label per code, shown in the language switcher."
  {"fr" "Français"
   "en" "English"})

(def og-locale
  "OpenGraph `og:locale` per code (SEO)."
  {"fr" "fr_FR"
   "en" "en_US"})

(defn normalize
  "Coerce a raw language value (string or keyword, any case) to a supported code,
  else the default."
  [lang]
  (let [l (some-> lang
                  name
                  str/lower-case)]
    (if (contains? supported-langs l) l default-lang)))

(def ^:private lang-alt
  "Regex alternation of the supported codes (e.g. `fr|en`), so detection follows
  `languages` with no separate hardcoded list."
  (str/join "|" languages))

(def ^:private cookie-re (re-pattern (str "(?i)\\blang=(?:%3A|:)?(" lang-alt ")\\b")))

(def ^:private accept-re (re-pattern (str "(?i)\\b(" lang-alt ")\\b")))

(defn- header
  "Case-insensitive lookup of a Ring header."
  [req header-name]
  (some (fn [[k v]] (when (= header-name (str/lower-case (name k))) v)) (:headers req)))

(defn cookie-lang
  "Return the `lang` cookie value if present and recognized. Accepts both the
  current `lang=fr` form and the legacy `lang=:fr` / `lang=%3Afr` forms."
  [req]
  (some-> (header req "cookie")
          (->> (re-find cookie-re))
          second
          str/lower-case))

(defn accept-lang
  "Pick the first supported language from `Accept-Language`."
  [req]
  (some->> (header req "accept-language")
           (re-seq accept-re)
           first
           second
           str/lower-case))

(defn pick-lang
  "Resolve a request's language: cookie → Accept-Language → `default-lang`."
  [req]
  (or (cookie-lang req) (accept-lang req) default-lang))
