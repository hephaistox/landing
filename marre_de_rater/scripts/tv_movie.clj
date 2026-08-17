(ns mdr.scripts.tv-movie
  "DISPOSABLE sandbox — UC-A (\"I'm waiting to be able to find/watch this\"), TV source.

  NOT wired into the app. Throwaway code to feel out what a real TV probe costs, per
  the Marre-de-rater design doc (Q2/Q3). Delete freely.

  Source: epg.pw's hosted France XMLTV — one ~16 MB file, ~42k programmes, daily
  refresh, no API key. Programmes carry title/desc/start/stop + a channel id whose
  display-name we resolve. There is NO <category>/genre, so this feed can only tell
  feature-length (movie) from episodic by DURATION — see the KIND section below.

  HONOURING P1/P2 (design doc). A probe is SOURCE-SPECIFIC and FORMAT-AWARE, and must
  emit only HIGH-VALUE hits. This TV probe exploits what the XMLTV format gives us,
  rather than a dumb title `contains?`:
    1. DURATION from start/stop — a feature runs ~60-240 min; episodic noise is short.
    2. CONTIGUOUS-TOKEN title match — the subject's words must appear as an unbroken
       run in the title (so \"seigneur des anneaux\" matches
       \"Le Seigneur des anneaux : les deux tours\").
    3. UPCOMING-ONLY — a movie that already aired is not worth a notification.

  CONTENT KIND (subject :kinds). The search declares what it wants: any of
  #{:movie :tv-show :anime}, or :all. This feed can only distinguish by duration:
    :movie   → feature-length slots (>= floor)
    :tv-show → episodic slots (< floor)
    :anime   → NOT DISTINGUISHABLE HERE (no genre metadata). Treated as episodic and
               FLAGGED low-confidence; real classification needs a metadata source
               (TMDB media_type + \"Animation\" genre) resolved at subject-identity
               time. This is concrete P2/Q4 evidence: the source's format bounds what
               kinds a probe can honour.

  Probe contract (from the doc): a PURE fn `(subject ctx -> [hit ...])` answering
  \"positive result right now?\"; returns ALL current matches, keeps NO memory — the
  (future) engine owns de-dup.

  --- REPL usage (from the landing repo root, in a `bb repl` dev REPL) ---
    (load-file \"marre_de_rater/scripts/tv_movie.clj\")
    (in-ns 'mdr.scripts.tv-movie)
    (load-epg!)                                  ; fetch+parse once (~16MB), cached
    (search \"batman\" #{:movie})                ; feature-length only
    (search \"batman\" #{:tv-show})              ; episodes
    (search \"batman\" :all)                     ; everything upcoming
    (tv-probe {:title \"batman\" :kinds #{:movie}} {})"
  (:require
   [clj-http.client :as http]
   [clojure.string  :as str]
   [clojure.xml     :as xml])
  (:import (java.text Normalizer Normalizer$Form)
           (java.time Duration Instant ZonedDateTime)
           (java.time.format DateTimeFormatter)))

(def epg-url "https://epg.pw/xmltv/epg_FR.xml")

(def default-min-duration-min
  "Movie-length floor. Below this a TV slot is almost certainly an episode / short,
  not a feature — the primary high-value filter (P1)."
  60)

(def kinds "The content kinds a search may target. :all means any." #{:movie :tv-show :anime})

;; --- text + tokens -------------------------------------------------------

(defn normalize
  "Lowercase, strip accents/diacritics and collapse non-alphanumerics to single
  spaces, so \"Le Seigneur des Anneaux\" ~ \"seigneur des anneaux\"."
  [s]
  (-> (Normalizer/normalize (or s "") Normalizer$Form/NFD)
      (str/replace #"\p{M}+" "")
      str/lower-case
      (str/replace #"[^a-z0-9]+" " ")
      str/trim))

(defn- tokens
  [s]
  (->> (str/split (normalize s) #"\s+")
       (remove str/blank?)
       vec))

(defn- contiguous-subseq?
  "True when vector `sub` occurs as an unbroken run inside vector `whole`."
  [sub whole]
  (let [n (count sub)
        m (count whole)]
    (and (pos? n) (boolean (some #(= sub (subvec whole % (+ % n))) (range 0 (inc (- m n))))))))

(defn title-matches?
  "Does the subject title appear as a contiguous run of words in the programme title?
  Precise enough to keep sequels/subtitles (\"…: les deux tours\") while rejecting
  incidental substring hits."
  [subject-title programme-title]
  (contiguous-subseq? (tokens subject-title) (tokens programme-title)))

;; --- content kind (what this format can infer) ---------------------------

(defn- infer-kind
  "All this feed can infer from its format: :movie if feature-length, else :episodic
  (or :unknown when the slot has no usable duration). It CANNOT tell anime apart from
  any other episodic content."
  [duration-min floor]
  (cond
    (nil? duration-min) :unknown
    (>= duration-min floor) :movie
    :else :episodic))

(defn- wanted-set
  "Normalise a subject :kinds into a set (or :all). Default #{:movie}."
  [k]
  (cond
    (nil? k) #{:movie}
    (= :all k) :all
    (set? k) k
    (keyword? k) #{k}
    :else (set k)))

(defn- kind-match?
  "Does an inferred slot kind satisfy the requested kinds? :tv-show and :anime both
  map onto episodic here (anime is not separable from this source)."
  [wanted inferred]
  (or (= :all wanted)
      (and (contains? wanted :movie) (= inferred :movie))
      (and (or (contains? wanted :tv-show) (contains? wanted :anime)) (= inferred :episodic))))

;; --- fetch + parse the XMLTV --------------------------------------------

(def ^:private xmltv-fmt (DateTimeFormatter/ofPattern "yyyyMMddHHmmss Z"))

(defn- parse-ts
  "XMLTV stamp (\"20260708203000 +0000\") → Instant, or nil if unparseable."
  [s]
  (when s (try (.toInstant (ZonedDateTime/parse s xmltv-fmt)) (catch Exception _ nil))))

(defn- duration-min [^Instant a ^Instant b] (when (and a b) (.toMinutes (Duration/between a b))))

(defn- children [el tag] (filter #(= tag (:tag %)) (:content el)))
(defn- text [el] (when el (first (filter string? (:content el)))))

(defn parse-epg
  "Parse the XMLTV root into {:channels {id name} :programmes [prog ...]} where each
  prog is {:channel-id :channel :title :desc :start :stop :starts-at :duration-min}."
  [root]
  (let [els (:content root)
        names (into {}
                    (for [c els
                          :when (= :channel (:tag c))]
                      [(get-in c [:attrs :id]) (text (first (children c :display-name)))]))
        progs (for [p els
                    :when (= :programme (:tag p))
                    :let [cid (get-in p [:attrs :channel])
                          raw-start (get-in p [:attrs :start])
                          raw-stop (get-in p [:attrs :stop])
                          st (parse-ts raw-start)
                          sp (parse-ts raw-stop)]]
                {:channel-id cid
                 :channel (names cid)
                 :title (text (first (children p :title)))
                 :desc (text (first (children p :desc)))
                 :start raw-start
                 :stop raw-stop
                 :starts-at st
                 :duration-min (duration-min st sp)})]
    {:channels names
     :programmes (vec progs)}))

(defn fetch-epg
  "GET + parse the France XMLTV. ~16 MB; call once and cache (see `load-epg!`)."
  []
  (let [resp (http/get epg-url
                       {:as :stream
                        :throw-exceptions false
                        :connection-timeout 10000
                        :socket-timeout 30000})]
    (when (= 200 (:status resp)) (parse-epg (xml/parse (:body resp))))))

(defonce ^{:doc "Cached parsed EPG so the REPL doesn't refetch 16MB each run."} epg (atom nil))

(defn load-epg! [] (reset! epg (fetch-epg)) (count (:programmes @epg)))

;; --- the probe under test ------------------------------------------------

(defn tv-probe
  "PURE, source-specific, high-value TV probe.
  subject: {:title \"...\" :kinds <#{:movie :tv-show :anime} | :all>} (:kinds default #{:movie}).
  ctx keys (all optional):
    :epg              parsed EPG to search (defaults to the cached `epg` atom)
    :min-duration-min movie/episodic split (defaults to `default-min-duration-min`)
    :now              Instant; airings before it are dropped (defaults to now)
  Returns a hit per UPCOMING airing whose title contains the subject title as a
  contiguous run of words AND whose inferred kind satisfies the requested kinds.
  Each hit carries :kind (inferred) and :kind-confidence (:high, or :low when the
  request was :anime — this feed cannot confirm anime)."
  [subject ctx]
  (let [want-title (:title subject)
        _ (when (str/blank? want-title)
            (throw (ex-info "subject :title is blank" {:subject subject})))
        wanted (wanted-set (:kinds subject))
        anime-asked? (and (set? wanted) (contains? wanted :anime))
        {:keys [min-duration-min now]
         :or {min-duration-min default-min-duration-min}}
        ctx
        now (or now (Instant/now))
        progs (:programmes (or (:epg ctx) @epg))]
    (vec
     (for [p progs
           :let [dur (:duration-min p)
                 inst (:starts-at p)
                 inferred (infer-kind dur min-duration-min)]
           :when (and (:title p)
                      (title-matches? want-title (:title p)) ; precise (P2)
                      (kind-match? wanted inferred)                 ; kind-scoped
                      (or (nil? inst) (not (.isBefore inst now))))] ; upcoming only
       {:hit-id (str "tv:" (:channel-id p) ":" (:start p) ":" (normalize (:title p)))
        :title (:title p)
        :kind inferred
        :kind-confidence (if (and anime-asked? (= inferred :episodic)) :low :high)
        :when (:start p)
        :starts-at inst
        :duration-min dur
        :where (:channel p)
        :desc (:desc p)
        :subject subject}))))

;; --- REPL conveniences ---------------------------------------------------

(defn search
  "Run the probe for a bare title + optional kinds; print the airings found.
  e.g. (search \"batman\" #{:movie}) / (search \"batman\" :all) / (search \"batman\")."
  ([title] (search title #{:movie}))
  ([title kinds*]
   (let [hits (tv-probe {:title title
                         :kinds kinds*}
                        {})]
     (println (format "%d hit(s) for %s kinds=%s:" (count hits) (pr-str title) (pr-str kinds*)))
     (when (some #(= :low (:kind-confidence %)) hits)
       (println
        "  ⚠ anime requested but this feed has no genre — kind unconfirmed (low confidence)"))
     (doseq [{:keys [when where duration-min kind title]} hits]
       (println (format "  %s  %4smin  %-9s  %-22s  %s"
                        when
                        (or duration-min "?")
                        (name kind)
                        (or where "?")
                        title)))
     hits)))

(defn sample-titles
  "n distinct programme titles from the loaded EPG, to eyeball what's searchable."
  [n]
  (->> (:programmes @epg)
       (keep :title)
       distinct
       (take n)
       vec))

(comment
  (load-epg!)
  (search "batman " #{:movie})   ; feature-length only
  (search "batman " #{:tv-show}) ; episodes
  (search "batman " :all)        ; everything upcoming
  (tv-probe {:title "batman
             "
             :kinds #{:movie}}
            {})
  (sample-titles 3)
  ;;
)
