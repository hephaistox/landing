(ns landing.agora.seo
  "Server-rendered SEO for Agora's public pages.

  Crawlers and social-media unfurlers read the raw HTML, before the SPA runs, so both the
  discoverable metadata (title, description, OpenGraph, schema.org JSON-LD) injected into
  the shell `<head>` AND a minimal static content **body** (`document-body`: prose with
  citation links, input/successor edges, references) placed inside `#agora-app` are
  rendered at serve time — the SPA replaces the body on mount. A sitemap is generated from
  the DB so every permalink is crawlable."
  (:require
   [cheshire.core                   :as json]
   [clojure.string                  :as str]
   [env]
   [landing.agora.document.identity :as di]
   [landing.agora.document.kind     :as dk]
   [landing.language                :as language]))

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

(defn- key-of
  "A document's permalink key — `<cid>~<title-slug>` (or bare `<cid>` when the title yields
  no slug). `cid` is the document's `name`; every char is URL-path-safe (`[a-z0-9-~]`), so
  no encoding is needed. The slug tracks the current title; resolution keeps only the cid."
  [cid title]
  (di/permalink-slug cid title))

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
   "url" (str base "/agora/" lang "/ki/" (key-of name title) "/" major)})

(defn- ref->citation
  "A schema.org CreativeWork for a bibliographic reference (a cited external source):
  the source's title, its author (Person), publication year and publisher — declaring the
  document's external provenance as linked data (`citation`)."
  [{:keys [title year editor author-name url]}]
  (cond-> {"@type" "CreativeWork"
           "name" title}
    author-name (assoc "author"
                       {"@type" "Person"
                        "name" author-name})
    year (assoc "datePublished" (str year))
    (not (str/blank? url)) (assoc "url" url)
    (not (str/blank? editor)) (assoc "publisher"
                                     {"@type" "Organization"
                                      "name" editor})))

(defn- prose "A document's prose (the unified `:text` key)." [doc] (:text doc))

(defn- description-of
  "A ~160-char, single-line summary from the document's prose."
  [doc]
  (let [s (-> (or (prose doc) "")
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

(defn- hreflang-alts
  "`<link rel=alternate hreflang>` alternates for a `type` permalink `(name, major)` across
  the concept's languages `langs` (each `{:lang …}`), so search engines unify the language
  siblings (which share the cid `name`). The decorative slug uses the focal `title` for all
  alternates — resolution is by cid, so a per-language slug is only a nicety and each still
  resolves + canonicalizes correctly."
  [base type doc-name title major langs]
  (for [{l :lang} langs]
    (str "<link rel=\"alternate\" hreflang=\""
         (esc l)
         "\" href=\""
         (esc (str base "/agora/" l "/" type "/" (key-of doc-name title) "/" major))
         "\"/>")))

(defn ki-head
  "SEO `<head>` fragment for a KI permalink: title, description, canonical +
  hreflang alternates, OpenGraph, and an schema.org Article with name,
  description, datePublished and author."
  [base lang ki-name ki-major ki]
  (let [title (title-of ki ki-name)
        ;; the kind-guided opening (derived) precedes the body so the snippet reads as a
        ;; full sentence ("Sun Tzŭ believes that …") — in the doc's content language (`:lang ki`,
        ;; which may differ from the URL `lang` on a cross-language fallback), so it never mixes
        desc (description-of {:text (dk/compose-statement ki (:lang ki) (prose ki))})
        url (str base "/agora/" lang "/ki/" (key-of ki-name title) "/" ki-major)
        langs (into [{:lang lang}] (:translations ki))]
    (str/join
     "\n"
     (concat [(str "<title>" (esc title) " — Agora</title>")
              (meta-name "description" desc)
              (str "<link rel=\"canonical\" href=\"" (esc url) "\"/>")]
             ;; hreflang alternates across the concept's languages
             (hreflang-alts base "ki" ki-name title ki-major langs)
             [(meta-prop "og:type" "article")
              (meta-prop "og:site_name" "Agora")
              (meta-prop "og:title" title)
              (meta-prop "og:description" desc)
              (meta-prop "og:url" url)
              (meta-prop "og:locale" (get language/og-locale (language/normalize lang)))
              (meta-name "twitter:card" "summary")
              (json-ld
               (cond-> {"@context" "https://schema.org"
                        "@type" "Article"
                        "headline" title
                        "name" title
                        "description" desc
                        "inLanguage" lang
                        "url" url}
                 (prose ki) (assoc "articleBody" (prose ki))
                 (:published-at ki) (assoc "datePublished" (:published-at ki))
                 (:attributed-author ki) (assoc "author"
                                                {"@type" "Person"
                                                 "name" (:attributed-author ki)})
                 ;; the reasoning edges: this KI is derived from
                 ;; its input KIs (declared as linked works)
                 (seq (:inputs ki)) (assoc "isBasedOn" (mapv #(ref-entry base %) (:inputs ki)))
                 ;; external provenance: the sources it cites
                 (:source ki) (assoc "citation" (ref->citation (:source ki)))))]))))

(defn- body->text
  "Plain-text of an article body for a meta description: each `[[ki:…]]` citation
  token becomes its custom text (or the humanized KI name), so the description reads
  naturally instead of showing raw tokens."
  [body]
  (str/replace (or body "")
               di/cite-pattern
               (fn [[_ _type nm _lang _major txt]] (or txt (humanize nm)))))

(defn article-head
  "SEO `<head>` for an article permalink: title, description (from the body), canonical +
  hreflang alternates, OpenGraph and an schema.org Article."
  [base lang art-name art-major art]
  (let [title (title-of art art-name)
        desc (description-of {:text (body->text (prose art))})
        url (str base "/agora/" lang "/article/" (key-of art-name title) "/" art-major)
        langs (into [{:lang lang}] (:translations art))]
    (str/join "\n"
              (concat [(str "<title>" (esc title) " — Agora</title>")
                       (meta-name "description" desc)
                       (str "<link rel=\"canonical\" href=\"" (esc url) "\"/>")]
                      ;; hreflang alternates across the concept's languages
                      (hreflang-alts base "article" art-name title art-major langs)
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
                                  (:published-at art) (assoc "datePublished" (:published-at art))
                                  (:attributed-author art) (assoc "author"
                                                                  {"@type" "Person"
                                                                   "name" (:attributed-author art)})
                                  (:source art) (assoc "citation"
                                                       (ref->citation (:source art)))))]))))

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
  "Generic OpenGraph + **`robots noindex`** for the non-canonical public pages this serves:
  the rotating discover / articles feeds and the preferences page (thin aggregations of
  links to already-indexed permalinks — the sitemap enumerates the corpus and the home page
  is the indexable hub, so the feeds shouldn't compete for crawl budget), and the
  not-found fallbacks of the permalink/author shells. OpenGraph is kept so the pages still
  unfurl when shared."
  [base lang path title desc]
  (str/join "\n"
            [(str "<title>" (esc title) "</title>")
             (meta-name "description" desc)
             (meta-name "robots" "noindex")
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
  "Return `template` with the existing <title> dropped, `head` injected before </head>,
  and the document language set to `lang`. With a non-nil `body` (public document pages),
  the static server-rendered content is placed inside the `#agora-app` mount node — a
  crawler/no-JS agent sees it, and the SPA replaces it on mount."
  ([template head lang] (inject template head lang nil))
  ([template head lang body]
   (cond-> template
     :always (str/replace #"(?is)<title>.*?</title>" "")
     ;; `lang` is escaped here as defence-in-depth; callers also normalize it to a
     ;; supported code, so an attacker cannot break out of the attribute.
     :always (str/replace #"<html lang=\"[^\"]*\"" (str "<html lang=\"" (esc lang) "\""))
     :always (str/replace "</head>" (str head "\n</head>"))
     ;; The static body is wrapped in `#agora-ssr`. An inline head script adds `js` to
     ;; <html>, and `html.js #agora-ssr{display:none}` hides it before the first paint — so
     ;; a JS visitor never sees it flash (the SPA then replaces #agora-app on mount), a
     ;; no-JS visitor still sees it, and text crawlers read it regardless of CSS.
     body (str/replace "<div id=\"agora-app\"></div>"
                       (str "<div id=\"agora-app\"><div id=\"agora-ssr\">" body "</div></div>")))))

;; ---------------------------------------------------------------------------
;; Server-rendered content body (progressive enhancement)
;;
;; Public permalink pages ship a minimal, static HTML rendering of the document — its
;; prose (with citations as links), its input/successor edges, and its references — inside
;; #agora-app, so crawlers and no-JS agents see the content and can walk the reasoning
;; graph. The SPA replaces it on mount. Latest minor only; version history is JS-only.
;; ---------------------------------------------------------------------------

(defn- day
  "The date part (YYYY-MM-DD) of an ISO timestamp string, or nil."
  [x]
  (let [s (str x)] (when (seq s) (subs s 0 (min 10 (count s))))))

(defn- cite->link
  "Replacement fn for `di/cite-pattern`: an in-prose `[[ki:name(:lang)?@major]]` citation →
  an `<a>` to that KI's permalink (text = the custom label, else the humanized name). The link
  targets the citation's own language when the token carries one, else the citing doc's `lang`."
  [base lang]
  (fn [[_ tp nm lang-tok mj txt]]
    (str "<a href=\""
         (esc (str base "/agora/" (or lang-tok lang) "/" (or tp "ki") "/" (enc nm) "/" mj))
         "\">"
         (esc (or txt (humanize nm)))
         "</a>")))

(defn- prefix-pill-html
  "The kind-guided statement opening as an inline rounded box (matches the SPA read view's
  `prefix-pill`) — flags it as hard-coded scaffold while flowing inline with the prose."
  [prefix]
  (str "<span style=\"display:inline-block;background:#f4efe4;border:1px solid #d9c9a8;"
       "border-radius:0.4em;padding:0 0.4em;margin-right:0.35em;color:#8a7a55;"
       "font-weight:500;white-space:nowrap\">"
       (esc (str/trim prefix))
       "</span>"))

(defn- prose-html
  "The document's prose as HTML: escaped, with `[[ki:…]]` citations turned into links, structured
  into **paragraphs** (blank line separates; single line-break → `<br/>`) and **bullet lists**
  (`- `/`* ` lines) via the shared `dk/parse-blocks`, so it matches the SPA renderer. An
  optional `lead` HTML string is placed inline at the start of the first paragraph (the boxed
  statement prefix)."
  [base lang text lead]
  (let [blocks (dk/parse-blocks text)
        first-p? (= :p (:type (first blocks)))
        inline (fn [s]
                 (-> (esc s)
                     (str/replace di/cite-pattern (cite->link base lang))))
        block->html
        (fn [i blk]
          (if (= :ul (:type blk))
            (str "<ul>" (apply str (map #(str "<li>" (inline %) "</li>") (:items blk))) "</ul>")
            (str "<p>"
                 (when (and (zero? i) first-p?) lead)
                 (str/join "<br/>" (map inline (:lines blk)))
                 "</p>")))]
    (str (when (and lead (not first-p?)) (str "<p>" lead "</p>"))
         (str/join "\n" (map-indexed block->html blocks)))))

(defn- neighbour-li
  "A list item linking to a neighbouring document's permalink."
  [base {:keys [type name lang major title]}]
  (str "<li><a href=\""
       (esc (str base
                 "/agora/" lang
                 "/" (clojure.core/name (or type :ki))
                 "/" (key-of name title)
                 "/" major))
       "\">"
       (esc (if (str/blank? title) (humanize name) title))
       "</a></li>"))

(defn- neighbour-section
  [base heading items]
  (when (seq items)
    (str "<section><h2>"
         heading
         "</h2><ul>"
         (str/join (map #(neighbour-li base %) items))
         "</ul></section>")))

(defn document-body
  "A minimal, static HTML body for a public permalink — `doc` is a
  `document/fetch-by-major` view and `successors` a `document/resolve-successors` list.
  Server-rendered so crawlers/no-JS agents get the content and the reasoning-graph edges
  before the SPA runs; the SPA replaces #agora-app on mount. Latest minor only."
  [base
   lang
   {:keys [major minor kind author author-id published-at inputs source]
    :as doc}
   successors
   doc-name]
  (let [title* (title-of doc doc-name)
        home (str base "/agora/" lang)]
    (str
     "<main style=\"max-width:44em;margin:1.5em auto;padding:0 1em;font-family:system-ui,sans-serif;line-height:1.5\">"
     "<nav><a href=\""
     (esc home)
     "\">Agora</a> · <a href=\""
     (esc (str home "/discover"))
     "\">Discover</a></nav>"
     "<article>"
     "<h1>"
     (esc title*)
     "</h1>"
     "<p>"
     (when-not (str/blank? kind) (str "<strong>" (esc (humanize kind)) "</strong> · "))
     (cond
       author-id (str "<a href=\"" (esc (str home "/author/" author-id)) "\">" (esc author) "</a>")
       author (esc author)
       :else "")
     (when-let [d (day published-at)] (str " · <time>" (esc d) "</time>"))
     " · v"
     major
     "."
     minor
     "</p>"
     "<div>"
     ;; kind-guided opening (derived, not stored) — a boxed pill inline at the start of the
     ;; prose (nil for the free-form `inference` kind). See document-dk/statement-prefix-of.
     (prose-html base
                 lang
                 (prose doc)
                 ;; prefix in the doc's content language (`:lang doc`), not the URL `lang`
                 (when-let [prefix (dk/statement-prefix-of doc (:lang doc))]
                   (prefix-pill-html prefix)))
     "</div>"
     (neighbour-section base "Based on" inputs)
     (neighbour-section base "Used by" successors)
     (when-let [{:keys [title year editor author-name locator]} source]
       (str "<section><h2>Source</h2><ul><li>"
            (when author-name (str (esc author-name) ", "))
            "<cite>"
            (esc title)
            "</cite>"
            (when year (str " (" (esc year) ")"))
            (when-not (str/blank? editor) (str ", " (esc editor)))
            (when-not (str/blank? locator) (str " — " (esc locator)))
            "</li></ul></section>"))
     "</article></main>")))

(defn- author-name-of [profile] (let [d (:display-name profile)] (if (str/blank? d) "Author" d)))

(defn author-head
  "SEO `<head>` for a public author page: title, description, canonical, OpenGraph profile
  and a schema.org ProfilePage/Person."
  [base lang author-id profile]
  (let [nm (author-name-of profile)
        url (str base "/agora/" lang "/author/" author-id)
        desc (str "Documents and reasoning by " nm " on Agora.")]
    (str/join "\n"
              [(str "<title>" (esc nm) " — Agora</title>")
               (meta-name "description" desc)
               (str "<link rel=\"canonical\" href=\"" (esc url) "\"/>")
               (meta-prop "og:type" "profile")
               (meta-prop "og:site_name" "Agora")
               (meta-prop "og:title" nm)
               (meta-prop "og:description" desc)
               (meta-prop "og:url" url)
               (meta-prop "og:locale" (get language/og-locale (language/normalize lang)))
               (json-ld {"@context" "https://schema.org"
                         "@type" "ProfilePage"
                         "mainEntity" (cond-> {"@type" "Person"
                                               "name" nm
                                               "url" url}
                                        (not (str/blank? (:avatar-url profile)))
                                        (assoc "image" (:avatar-url profile)))})])))

(defn author-body
  "Static HTML for a public author page — the author card + a linked list of their
  documents — so crawlers get an author→documents hub. `documents` are `by-author` cards."
  [base lang profile documents]
  (let [nm (author-name-of profile)
        home (str base "/agora/" lang)]
    (str
     "<main style=\"max-width:44em;margin:1.5em auto;padding:0 1em;font-family:system-ui,sans-serif;line-height:1.5\">"
     "<nav><a href=\""
     (esc home)
     "\">Agora</a> · <a href=\""
     (esc (str home "/discover"))
     "\">Discover</a></nav>"
     "<h1>"
     (esc nm)
     "</h1>"
     (when-let [d (day (:created-at profile))] (str "<p>Member since <time>" (esc d) "</time></p>"))
     "<h2>Documents</h2>"
     (if (seq documents)
       (str "<ul>"
            (str/join (map (fn [d] (neighbour-li base (update d :lang #(or % lang)))) documents))
            "</ul>")
       "<p>No documents yet.</p>")
     "</main>")))

;; ---------------------------------------------------------------------------
;; Sitemap
;; ---------------------------------------------------------------------------

(defn- iso-date
  [x]
  (some-> x
          str
          (subs 0 (min 10 (count (str x))))))

(defn sitemap-xml
  "A sitemap of every document permalink — all types (one <url> per language, cross-linked
  with hreflang alternates) — plus the per-language home page. The discover/articles feeds
  are `noindex`, so they are excluded (a sitemap must not list noindex URLs). `rows` are
  {:type :name :major :lang :lastmod} from the DB, one per lineage."
  [base rows]
  (let [by-concept (group-by (juxt :type :name :major) rows)
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
    (str
     "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
     "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"\n"
     "        xmlns:xhtml=\"http://www.w3.org/1999/xhtml\">\n"
     ;; home / landing pages (the only indexable hub; the discover/articles feeds are
     ;; `noindex`, so they are deliberately NOT listed here)
     (str/join (for [l language/languages]
                 (url (str "/agora/" l)
                      nil
                      (for [a language/languages]
                        {:lang a
                         :path (str "/agora/" a)}))))
     ;; document permalinks (all types), hreflang-linked across their languages. Each
     ;; language version's URL carries its own title-slug (`<cid>~<slug>`); resolution
     ;; is by the shared cid `nm`.
     (str/join
      (for [[[type nm mj] versions] by-concept
            {:keys [lang lastmod title]} versions
            :let [vpath (fn [v]
                          (str "/agora/" (:lang v) "/" type "/" (key-of nm (:title v)) "/" mj))
                  alts (map (fn [v]
                              {:lang (:lang v)
                               :path (vpath v)})
                            versions)]]
        (url (str "/agora/" lang "/" type "/" (key-of nm title) "/" mj) (iso-date lastmod) alts)))
     "</urlset>\n")))
