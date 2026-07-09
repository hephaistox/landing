(ns mdr.scripts.engine
  "DISPOSABLE sandbox ENGINE — turns user INTENTS into notifications, in clear layers.

  Input is a user intent (see intents.edn): {:query :kind :lang :owner} — WHAT the
  user doesn't want to miss, with NO probe/source details. The engine decides the rest.

  The five layers (design doc pipeline, made concrete):

    intents ─▶ (1) ASSIGN ─▶ (2) EXECUTE ─▶ (3) DEDUPE ─▶ (4) GATHER ─▶ (5) NOTIFY
               route+translate   run probes    vs MEMORY     per probe    println

    1. ASSIGN   — for each intent, pick the probes that could find it and TRANSLATE
                  the intent into each probe's own input. The user never names sources.
    2. EXECUTE  — run each assigned probe → events.
    3. DEDUPE   — drop events already in MEMORY (previously discovered); the rest are
                  new. Memory persists across ticks so nothing is announced twice.
    4. GATHER   — collect the new events, grouped per probe.
    5. NOTIFY   — every new event triggers a notification (a println for now).

  Two probes are registered: :tv (mdr.scripts.tv-movie) and :vod (mdr.scripts.vod-movie).
  The assign layer routes each intent to whichever can serve it — e.g. an :en intent
  the FR-only TV feed rejects is still served by the region-agnostic VOD probe.

  --- REPL usage (landing repo root; VOD needs TMDB_API_KEY in env) ---
    (load-file \"marre_de_rater/scripts/tv_movie.clj\")   ; load probes FIRST
    (load-file \"marre_de_rater/scripts/vod_movie.clj\")
    (load-file \"marre_de_rater/scripts/engine.clj\")
    (in-ns 'mdr.scripts.engine)
    (tick! \"marre_de_rater/scripts/intents.edn\")         ; 1st run → notifications
    (tick! \"marre_de_rater/scripts/intents.edn\")         ; 2nd run → nothing new"
  (:require
   [clojure.edn :as edn]))

;; --- bridge to the (load-file'd) probe namespaces ------------------------

(defn- tv-var [n] (resolve (symbol "mdr.scripts.tv-movie" (name n))))
(defn- vod-var [n] (resolve (symbol "mdr.scripts.vod-movie" (name n))))

(defn- ensure-loaded!
  []
  (when-not (tv-var 'tv-probe)
    (throw (ex-info "load marre_de_rater/scripts/tv_movie.clj FIRST" {})))
  (when-not (vod-var 'vod-probe)
    (throw (ex-info "load marre_de_rater/scripts/vod_movie.clj FIRST" {}))))

(defn- ensure-epg!
  []
  (when (nil? @@(tv-var 'epg)) (println "engine: loading EPG (~16MB)…") ((tv-var 'load-epg!))))

(defn- lang->region
  [lang]
  (case lang
    :fr :fr
    :en :us
    :fr))

;; --- probe registry ------------------------------------------------------
;; Each probe declares WHAT it can find (:finds?) and HOW to turn a user intent into
;; its own input (:translate). This is what lets layer 1 route intents to probes
;; without the user ever naming a source.

(def registry
  [{:id :tv
    ;; the FR TV feed can find video content, in French only
    :finds? (fn [intent]
              (and (= :fr (:lang intent))
                   (contains? #{:movie :tv-show :anime :all} (:kind intent))))
    ;; user intent → tv-probe subject
    :translate (fn [intent]
                 {:title (:query intent)
                  :kinds (if (= :all (:kind intent)) :all #{(:kind intent)})})
    :run (fn [subject ctx] ((tv-var 'tv-probe) subject ctx))}
   {:id :vod
    ;; TMDB serves any language/region → finds video content regardless of :lang
    :finds? (fn [intent] (contains? #{:movie :tv-show :all} (:kind intent)))
    :translate (fn [intent]
                 {:title (:query intent)
                  :kinds (if (= :all (:kind intent)) :all #{(:kind intent)})})
    :run (fn [subject ctx] ((vod-var 'vod-probe) subject ctx))}])

;; --- MEMORY (persists across ticks; the engine's recollection) -----------

(defonce ^{:doc "event-ids already discovered — so nothing is announced twice."} memory (atom #{}))
(defn reset-memory! [] (reset! memory #{}))

;; --- data: the intents ---------------------------------------------------

(defn load-intents [path] (:intents (edn/read-string (slurp path))))

;; =========================================================================
;; LAYER 1 — ASSIGN: route+translate each intent to the probes that may find it
;; =========================================================================

(defn assign
  "For every intent, produce one assignment per probe that could find it, carrying the
  probe's translated input. → [{:intent :probe :run :subject} ...]"
  [intents]
  (for [intent intents
        probe registry
        :when ((:finds? probe) intent)]
    {:intent intent
     :probe (:id probe)
     :run (:run probe)
     :subject ((:translate probe) intent)}))

;; =========================================================================
;; LAYER 2 — EXECUTE: run each assigned probe → events (tagged with provenance)
;; =========================================================================

(defn execute
  "Run every assignment's probe and flatten to events. An event = one probe hit,
  tagged with its probe + originating intent. Each probe gets a ctx enriched with a
  :region derived from the intent's :lang."
  [assignments ctx]
  (vec (for [{:keys [intent probe run subject]} assignments
             :let [ectx (assoc ctx :region (lang->region (:lang intent)))]
             hit (run subject ectx)]
         {:event-id (:hit-id hit)
          :probe probe
          :intent-id (:id intent)
          :owner (:owner intent)
          :query (:query intent)
          :title (:title hit)
          :kind (:kind hit)
          :when (:when hit)
          :where (:where hit)
          :offer (:offer hit)
          :duration-min (:duration-min hit)})))

;; =========================================================================
;; LAYER 3 — DEDUPE: keep only events not already in MEMORY
;; =========================================================================

(defn dedupe-new
  "Events whose :event-id is NOT yet in memory. (Does not mutate memory — the caller
  records them once notified.)"
  [events]
  (vec (remove #(contains? @memory (:event-id %)) events)))

;; =========================================================================
;; LAYER 4 — GATHER: collect the new events per probe
;; =========================================================================

(defn gather
  "Group new events by their probe → {:tv [event ...], ...}."
  [events]
  (into (sorted-map) (group-by :probe events)))

;; =========================================================================
;; LAYER 5 — NOTIFY: every new event triggers a notification (println for now)
;; =========================================================================

(defn notify!
  "Emit a notification per new event. Placeholder for real delivery. TV events show the
  airing time; VOD events show the offer type (availability is a state, no time)."
  [events]
  (doseq [e events]
    (let [detail (if (:when e)
                   (str (:when e) " @ " (:where e)) ; TV airing
                   (str (name (or (:offer e) :available)) " @ " (:where e)))] ; VOD state
      (println (format "🔔 %-5s [%s→%s] %s — %s"
                       (str (name (:probe e)) ":")
                       (:query e)
                       (:owner e)
                       (:title e)
                       detail)))))

;; --- orchestration -------------------------------------------------------

(defn tick!
  "One engine cycle over the intents file, running all five layers. Returns the new
  events gathered per probe; prints a notification for each."
  ([intents-path] (tick! intents-path {}))
  ([intents-path ctx]
   (ensure-loaded!)
   (let [intents (load-intents intents-path)
         assignments (assign intents)     ; L1
         _ (when (some #(= :tv (:probe %)) assignments) (ensure-epg!))
         events (execute assignments ctx) ; L2
         new-events (dedupe-new events)   ; L3
         by-probe (gather new-events)]    ; L4
     (doseq [[probe evs] by-probe]
       (println (format "── %d new event(s) via %s ──" (count evs) (name probe)))
       (notify! evs))                     ; L5
     (when (empty? new-events) (println "(nothing new)"))
     (swap! memory into (map :event-id new-events)) ; remember
     by-probe)))
