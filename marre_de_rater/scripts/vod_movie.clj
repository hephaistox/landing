(ns mdr.scripts.vod-movie
  "DISPOSABLE sandbox — UC-A (\"I'm waiting to be able to find/watch this\"), VOD source.

  NOT wired into the app. Throwaway code to feel out what a real VOD probe costs, per
  the Marre-de-rater design doc (Q2/Q3). Delete freely.

  Source: TMDB (themoviedb.org), free API key. Two calls per probe:
    1. GET /search/{movie|tv}?query=…   → resolve the title to a TMDB IDENTITY
       (id + official title + release date + genres). This is the CANONICAL SUBJECT
       IDENTITY the design keeps asking for (Q11) and the KIND resolver (Q10):
       media_type (movie vs tv) + genres (incl. Animation) come straight from here.
    2. GET /{movie|tv}/{id}/watch/providers  → per-region provider availability
       (JustWatch data): {:results {:FR {:flatrate [\"Netflix\" …] :rent … :buy …}}}.

  So we do NOT log into the user's Netflix/Amazon — availability is looked up by
  region (Q8 resolved).

  HONOURING P1/P2. The probe is source-specific: it exploits TMDB's identity + genre
  to disambiguate the work (fixing the TV probe's \"Scooby-Doo & Batman\" gap), and
  returns ALL providers — the interpretation layer narrows/widens (monetization, Q9).

  STATE vs EVENT. VOD availability is a STATE, not a timed occurrence: :when is nil and
  :hit-id carries NO timestamp, so the engine notifies ONCE when a provider appears and
  not again while it stays available (contrast the TV probe, whose hit-id includes the
  airing time → each airing notifies). This is the parked Q2b distinction, falling out
  naturally from hit-id design.

  Probe contract: PURE `(subject ctx -> [hit ...])`; returns ALL current matches, no
  memory — the engine owns de-dup.

  API key: read from ctx :api-key, else env TMDB_API_KEY. Nothing secret in this file.

  --- REPL usage (landing repo root) ---
    (load-file \"marre_de_rater/scripts/vod_movie.clj\")
    (in-ns 'mdr.scripts.vod-movie)
    (vod-probe {:title \"Dune\" :kinds #{:movie}} {:region :fr})
    (search \"Dune\" :fr)"
  (:require
   [clj-http.client :as http]
   [clojure.string  :as str]))

(def ^:private base "https://api.themoviedb.org/3")

(def ^:private lang-of
  {:fr "fr-FR"
   :en "en-US"
   :us "en-US"
   :gb "en-GB"})
(def ^:private region-code
  {:fr "FR"
   :us "US"
   :en "US"
   :gb "GB"})

(defn- api-key [ctx] (or (:api-key ctx) (System/getenv "TMDB_API_KEY")))

(defn- GET
  "GET a TMDB endpoint, JSON→map, nil on any non-200/failure (P1: fail quiet)."
  [url params]
  (try (let [resp (http/get url
                            {:query-params params
                             :as :json
                             :throw-exceptions false
                             :connection-timeout 8000
                             :socket-timeout 8000})]
         (when (= 200 (:status resp)) (:body resp)))
       (catch Exception _ nil)))

(defn tmdb-search
  "Resolve a title to its TMDB identity for `media` (\"movie\"|\"tv\"). Returns the top
  match (keys: :id :title/:name :release_date/:first_air_date :original_title
  :genre_ids :popularity) or nil. The top result is TMDB's popularity-ranked best guess."
  [key media title lang]
  (when (and key (not (str/blank? title)))
    (-> (GET (str base "/search/" media)
             {:api_key key
              :query title
              :language (or lang "en-US")
              :include_adult false})
        :results
        first)))

(defn tmdb-providers
  "watch/providers for a resolved id → the region block
  {:link .. :flatrate [{:provider_name ..} ..] :rent [..] :buy [..]} or nil."
  [key media id region]
  (when (and key id)
    (-> (GET (str base "/" media "/" id "/watch/providers") {:api_key key})
        :results
        (get (keyword (region-code region "US"))))))

(defn vod-probe
  "PURE VOD probe. subject {:title \"…\" :kinds <#{:movie}|#{:tv-show}|:all>}.
  ctx {:region :fr|:us|… :api-key <str>}. Resolves the title on TMDB then emits a hit
  per provider offering it in the region (flatrate/rent/buy). Returns ALL providers."
  [subject ctx]
  (when (str/blank? (:title subject))
    (throw (ex-info "subject :title is blank" {:subject subject})))
  (let [key (api-key ctx)
        region (or (:region ctx) :fr)
        kind (let [k (:kinds subject)] (if (set? k) (first k) :movie))
        media (if (= :tv-show kind) "tv" "movie")
        lang (lang-of region "en-US")]
    (if-not key
      (do (println "vod-probe: no TMDB_API_KEY set — skipping") [])
      (when-let [found (tmdb-search key media (:title subject) lang)]
        (let [id (:id found)
              official (or (:title found) (:name found))
              block (tmdb-providers key media id region)
              link (:link block)]
          (vec (for [offer [:flatrate :rent :buy]
                     prov (get block offer)]
                 {:hit-id (str "vod:tmdb:" media
                               ":" id
                               ":" (name region)
                               ":" (name offer)
                               ":" (:provider_id prov))
                  :title official
                  :kind kind
                  :offer offer
                  :where (:provider_name prov)
                  :when nil ; availability is a STATE, not a timed event
                  :url link
                  :tmdb-id id
                  :subject subject})))))))

;; --- REPL convenience ----------------------------------------------------

(defn search
  "Run the probe for a bare title + region and print the providers found."
  ([title] (search title :fr))
  ([title region]
   (let [hits (vod-probe {:title title
                          :kinds #{:movie}}
                         {:region region})]
     (println
      (format "%d provider-offer(s) for %s in %s:" (count hits) (pr-str title) (name region)))
     (doseq [{:keys [offer where title]} hits]
       (println (format "  %-9s %-16s %s" (name offer) where title)))
     hits)))
