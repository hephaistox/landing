(ns landing.agora.seo
  "Server-rendered SEO for Agora's public pages (#39).

  Crawlers and social-media unfurlers read the raw HTML, before the SPA runs, so
  the discoverable metadata (title, description, OpenGraph, schema.org JSON-LD) is
  injected into the shell `<head>` at serve time — and a sitemap is generated from
  the DB so every KI permalink is crawlable."
  (:require
   [cheshire.core        :as json]
   [clojure.string       :as str]
   [env]
   [landing.agora.domain :as domain]
   [landing.language     :as language]))

(def ^:private allowed-host-re
  "Hosts we will echo into absolute URLs in production — the hephaistox domains
  (.com/.fr/.pl, incl. subdomains) and Clever Cloud, mirroring the CORS allowlist
  in `env`. Anything else is an unrecognized/spoofed Host and is not trusted."
  #"(?i)^([a-z0-9-]+\.)*(hephaistox\.(?:com|fr|pl)|cleverapps\.io)(?::\d+)?$")

(defn base-url
  "The public origin for absolute URLs. Prefers OAUTH_BASE_URL (configured per
  deployment). In dev it derives scheme+host from the request. In production it
  trusts the request Host only when it matches a known hephaistox/Clever Cloud
  domain (so .com/.fr/.pl deployments each get their own canonical origin) and
  otherwise falls back to a fixed origin — an attacker-supplied Host can never
  poison canonical/OpenGraph URLs or the sitemap."
  [req]
  (or (System/getenv "OAUTH_BASE_URL")
      (let [host (get-in req [:headers "host"])]
        (cond
          (not= :prod env/env) (str (name (or (:scheme req) :http)) "://" (or host "localhost"))
          (and host (re-matches allowed-host-re host)) (str "https://" host)
          :else "https://hephaistox.fr"))))

(defn- enc
  "URL-encode a path segment (spaces as %20), matching the SPA's encodeURIComponent
  so generated URLs equal the ones the app links to."
  [s]
  (-> (java.net.URLEncoder/encode (str s) "UTF-8")
      (str/replace "+" "%20")))

(defn- esc
  "Escape text for use inside an HTML attribute or element."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- humanize
  [s]
  (let [t (-> (or s "")
              (str/replace #"[-_]+" " ")
              str/trim)]
    (if (str/blank? t) (str s) (str (str/upper-case (subs t 0 1)) (subs t 1)))))

(defn- title-of [ki ki-name] (let [t (:title ki)] (if (str/blank? t) (humanize ki-name) t)))

(defn- ref-entry
  "A schema.org CreativeWork reference to a neighbouring KI — its title and its
  permalink — so the reasoning graph's edges are declared as linked data."
  [base {:keys [name title major lang]}]
  {"@type" "CreativeWork"
   "name" (if (str/blank? title) (humanize name) title)
   "url" (str base "/agora/" lang "/ki/" (enc name) "/" major)})

(defn- description-of
  "A ~160-char, single-line summary from the KI's statement."
  [ki]
  (let [s (-> (or (:output-statement ki) "")
              (str/replace #"\s+" " ")
              str/trim)]
    (if (> (count s) 160) (str (subs s 0 157) "…") s)))

(defn- meta-prop [prop content] (str "<meta property=\"" prop "\" content=\"" (esc content) "\"/>"))
(defn- meta-name [nm content] (str "<meta name=\"" nm "\" content=\"" (esc content) "\"/>"))

(defn- json-ld
  "A schema.org JSON-LD <script>, with `</` escaped so it can't break out of the
  script element."
  [m]
  (str "<script type=\"application/ld+json\">"
       (-> (json/generate-string m)
           (str/replace "</" "<\\/"))
       "</script>"))

(defn ki-head
  "SEO `<head>` fragment for a KI permalink: title, description, canonical +
  hreflang alternates, OpenGraph, and an schema.org Article with name,
  description, datePublished and author."
  [base lang ki-name ki-major ki]
  (let [title (title-of ki ki-name)
        desc (description-of ki)
        url (str base "/agora/" lang "/ki/" (enc ki-name) "/" ki-major)
        langs (into [{:lang lang}] (:translations ki))]
    (str/join
     "\n"
     (concat [(str "<title>" (esc title) " — Agora</title>")
              (meta-name "description" desc)
              (str "<link rel=\"canonical\" href=\"" (esc url) "\"/>")]
             ;; hreflang alternates across the concept's languages
             (for [{l :lang} langs]
               (str "<link rel=\"alternate\" hreflang=\""
                    (esc l)
                    "\" href=\""
                    (esc (str base "/agora/" l "/ki/" (enc ki-name) "/" ki-major))
                    "\"/>"))
             [(meta-prop "og:type" "article")
              (meta-prop "og:site_name" "Agora")
              (meta-prop "og:title" title)
              (meta-prop "og:description" desc)
              (meta-prop "og:url" url)
              (meta-prop "og:locale" (get language/og-locale (language/normalize lang)))
              (meta-name "twitter:card" "summary")
              (json-ld (cond-> {"@context" "https://schema.org"
                                "@type" "Article"
                                "headline" title
                                "name" title
                                "description" desc
                                "inLanguage" lang
                                "url" url}
                         (:output-statement ki) (assoc "articleBody" (:output-statement ki))
                         (:published-at ki) (assoc "datePublished" (:published-at ki))
                         (:author ki) (assoc "author"
                                             {"@type" "Person"
                                              "name" (:author ki)})
                         ;; the reasoning edges: this KI is derived from
                         ;; its input KIs (declared as linked works)
                         (seq (:inputs ki)) (assoc "isBasedOn"
                                                   (mapv #(ref-entry base %) (:inputs ki)))))]))))

(defn- body->text
  "Plain-text of an article body for a meta description: each `[[ki:…]]` citation
  token becomes its custom text (or the humanized KI name), so the description reads
  naturally instead of showing raw tokens."
  [body]
  (str/replace (or body "") domain/cite-pattern (fn [[_ nm _major txt]] (or txt (humanize nm)))))

(defn article-head
  "SEO `<head>` for an article permalink: title, description (from the body),
  canonical, OpenGraph and an schema.org Article."
  [base lang art-name art-major art]
  (let [title (title-of art art-name)
        desc (description-of {:output-statement (body->text (:body art))})
        url (str base "/agora/" lang "/article/" (enc art-name) "/" art-major)]
    (str/join "\n"
              [(str "<title>" (esc title) " — Agora</title>")
               (meta-name "description" desc)
               (str "<link rel=\"canonical\" href=\"" (esc url) "\"/>")
               (meta-prop "og:type" "article")
               (meta-prop "og:site_name" "Agora")
               (meta-prop "og:title" title)
               (meta-prop "og:description" desc)
               (meta-prop "og:url" url)
               (meta-prop "og:locale" (get language/og-locale (language/normalize lang)))
               (meta-name "twitter:card" "summary")
               (json-ld (cond-> {"@context" "https://schema.org"
                                 "@type" "Article"
                                 "headline" title
                                 "name" title
                                 "description" desc
                                 "inLanguage" lang
                                 "url" url}
                          (:published-at art) (assoc "datePublished" (:published-at art))
                          (:author art) (assoc "author"
                                               {"@type" "Person"
                                                "name" (:author art)})))])))

(def ^:private home-copy
  "Localized marketing title/description for the home/landing page — mirrors the SPA
  hero copy (landing.agora.frontend.i18n `:landing/headline` / `:landing/subtitle`).
  Kept here because SEO is server-rendered before the SPA runs."
  {"fr"
   {:title "Agora — Stockez le raisonnement, pas seulement la conclusion"
    :desc
    "Agora est un graphe public d'étapes de raisonnement contestables — chaque affirmation traçable jusqu'aux étapes qui la fondent, chaque terme jusqu'à sa définition."}
   "en"
   {:title "Agora — Store the reasoning, not just the conclusion"
    :desc
    "Agora is a public graph of challengeable reasoning steps — every claim traceable to the steps it stands on, every term to its definition."}})

(defn home-head
  "SEO `<head>` for the Agora home/landing page: a localized marketing title and
  description, canonical + hreflang alternates across languages, and website
  OpenGraph."
  [base lang]
  (let [{:keys [title desc]}
        (get home-copy (language/normalize lang) (get home-copy language/default-lang))
        url (str base "/agora/" lang)]
    (str/join "\n"
              (concat [(str "<title>" (esc title) "</title>")
                       (meta-name "description" desc)
                       (str "<link rel=\"canonical\" href=\"" (esc url) "\"/>")]
                      (for [l language/languages]
                        (str "<link rel=\"alternate\" hreflang=\""
                             (esc l)
                             "\" href=\""
                             (esc (str base "/agora/" l))
                             "\"/>"))
                      [(meta-prop "og:type" "website")
                       (meta-prop "og:site_name" "Agora")
                       (meta-prop "og:title" title)
                       (meta-prop "og:description" desc)
                       (meta-prop "og:url" url)
                       (meta-prop "og:locale" (get language/og-locale (language/normalize lang)))
                       (meta-name "twitter:card" "summary")]))))

(defn generic-head
  "Generic OpenGraph for a non-KI public page (e.g. discover)."
  [base lang path title desc]
  (str/join "\n"
            [(str "<title>" (esc title) "</title>")
             (meta-name "description" desc)
             (meta-prop "og:type" "website")
             (meta-prop "og:site_name" "Agora")
             (meta-prop "og:title" title)
             (meta-prop "og:description" desc)
             (meta-prop "og:url" (str base "/agora/" lang path))
             (meta-prop "og:locale" (get language/og-locale (language/normalize lang)))]))

(defn noindex-head
  "Head for an authoring/app page (new, KI-by-id, article, admin): a plain title
  and a `robots noindex` — these are not public content and must not be crawled or
  compete with the canonical permalink."
  [title]
  (str "<title>" (esc title) "</title>\n<meta name=\"robots\" content=\"noindex\"/>"))

(defn inject
  "Return `template` with the existing <title> dropped, `head` injected before
  </head>, and the document language set to `lang`."
  [template head lang]
  (-> template
      (str/replace #"(?is)<title>.*?</title>" "")
      ;; `lang` is escaped here as defence-in-depth; callers also normalize it to a
      ;; supported code, so an attacker cannot break out of the attribute.
      (str/replace #"<html lang=\"[^\"]*\"" (str "<html lang=\"" (esc lang) "\""))
      (str/replace "</head>" (str head "\n</head>"))))

;; ---------------------------------------------------------------------------
;; Sitemap
;; ---------------------------------------------------------------------------

(defn- iso-date
  [x]
  (some-> x
          str
          (subs 0 (min 10 (count (str x))))))

(defn sitemap-xml
  "A sitemap of every KI permalink (one <url> per language, cross-linked with
  hreflang alternates) plus the per-language discover pages. `rows` are
  {:name :major :lang :lastmod} from the DB."
  [base rows]
  (let [by-concept (group-by (juxt :name :major) rows)
        url (fn [path lastmod alts]
              (str "  <url>\n"
                   "    <loc>"
                   (esc (str base path))
                   "</loc>\n"
                   (str/join (for [a alts]
                               (str "    <xhtml:link rel=\"alternate\" hreflang=\""
                                    (:lang a)
                                    "\" href=\""
                                    (esc (str base (:path a)))
                                    "\"/>\n")))
                   (when lastmod (str "    <lastmod>" lastmod "</lastmod>\n"))
                   "    <changefreq>weekly</changefreq>\n"
                   "  </url>\n"))]
    (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
         "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"\n"
         "        xmlns:xhtml=\"http://www.w3.org/1999/xhtml\">\n"
         ;; home / landing pages
         (str/join (for [l language/languages]
                     (url (str "/agora/" l)
                          nil
                          (for [a language/languages]
                            {:lang a
                             :path (str "/agora/" a)}))))
         ;; discover pages
         (str/join (for [l language/languages]
                     (url (str "/agora/" l "/discover")
                          nil
                          (for [a language/languages]
                            {:lang a
                             :path (str "/agora/" a "/discover")}))))
         ;; KI permalinks
         (str/join (for [[[nm mj] versions] by-concept
                         {:keys [lang lastmod]} versions
                         :let [path (fn [l] (str "/agora/" l "/ki/" (enc nm) "/" mj))
                               alts (map (fn [v]
                                           {:lang (:lang v)
                                            :path (path (:lang v))})
                                         versions)]]
                     (url (path lang) (iso-date lastmod) alts)))
         "</urlset>\n")))
