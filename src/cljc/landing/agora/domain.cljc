(ns landing.agora.domain
  "The Agora knowledge-graph domain: the vocabulary and the pure rules for wiring KIs
  together, shared by the backend and the frontend (cljc).

  No I/O and no persistence format. Effectful lookups (`latest-of`, load, persist)
  and EDN (de)serialization are supplied by the adapters — `landing.agora.ki` on the
  server, the SPA on the client. Persistence (SQL, cache) and transport (HTTP) are
  adapters *around* this core, so the wiring logic lives in exactly one place and one
  technology.

  A KI's identity is its **TNLR** = (type, name, lang, major), where `type` is the
  object type (`ki` / `objection`, the T); its latest minor is the current version.
  Its epistemic register is a separate `kind`. Its inputs are declared as TNLRs and
  each pinned to a concrete predecessor id.")

(def kinds
  "The epistemic `kind`s — canonical domain data for a KI's kind, in display order.
  Each carries its accent colour and its conceptual `family` (`derived` — has inputs;
  `verifiable` — settles at a date; `foundation` — a declared starting point). The set
  is NOT enforced by the DB; the API validates against it and the UI renders from it."
  [{:id :inference
    :color "#2c5aa0"
    :family :derived}
   {:id :prediction
    :color "#0b7285"
    :family :verifiable}
   {:id :postulate
    :color "#6741d9"
    :family :foundation}
   {:id :position
    :color "#b9770e"
    :family :foundation}
   {:id :belief
    :color "#2b8a3e"
    :family :foundation}
   {:id :credo
    :color "#c92a2a"
    :family :foundation}])

(def kind-ids "The kind ids (keywords), in display order." (mapv :id kinds))

(def kind-color
  "kind name (string) → accent colour."
  (into {} (map (juxt (comp name :id) :color)) kinds))

(def kind-family
  "kind name (string) → conceptual family (keyword)."
  (into {} (map (juxt (comp name :id) :family)) kinds))

(def object-types
  "The identity T values (object types sharing the single AGORA_NODE table)."
  [:ki :objection :article])

;; --- article KI-citation grammar ---------------------------------------------
;; A `[[ki:<name>@<major>]]` (or `…|custom text]]`) token in an article body cites a
;; KI. The grammar is defined once here so the article renderer (frontend) and the
;; citation extractor (backend) never drift.

(def cite-pattern
  "Regex for one in-body KI citation: capture groups are name, major, optional text."
  #"\[\[ki:([^@\]|]+)@(\d+)(?:\|([^\]]+))?\]\]")

(defn- parse-major
  [s]
  #?(:clj (Integer/parseInt s)
     :cljs (js/parseInt s 10)))

(defn cite-refs
  "The distinct KIs cited in `body`, as input declarations for a node in `lang`:
  a vector of TNLRs {:type \"ki\" :name … :lang lang :major …}, order-preserving and
  deduped by (name, major). These are exactly KI inputs, so an article reuses the
  whole input/pin/successor model."
  [body lang]
  (->> (re-seq cite-pattern (or body ""))
       (map (fn [[_ nm mj]]
              {:type "ki"
               :name nm
               :lang lang
               :major (parse-major mj)}))
       (distinct)
       (vec)))

;; --- TNLR --------------------------------------------------------------------

(defn tnlr
  "The TNLR of a node or ref map — {:type :name :lang :major} (type = object type)."
  [m]
  (select-keys m [:type :name :lang :major]))

(defn tnlr-key
  "A comparable/cacheable vector form of a TNLR: [type name lang major]."
  [m]
  [(:type m) (:name m) (:lang m) (:major m)])

(defn same-tnlr? [a b] (= (tnlr-key a) (tnlr-key b)))

;; --- inputs ------------------------------------------------------------------
;; An input has two halves that live in different places:
;;   - the DECLARATION — a TNLR — is authored, part of the KI's meaning, and lives in
;;     the immutable `content` (`:inputs [TNLR…]`). Changing it versions the KI.
;;   - the PIN — the resolved predecessor id — is derived (re-resolved when a
;;     predecessor gets a new minor) and lives in the mutable `computed`
;;     (`:pins {tnlr-key → id}`).

(def max-inputs
  "Cap on the number of declared inputs a single KI may carry. Inputs are added one
  at a time (each a new minor), with no natural bound, so this protects the read
  model — the envelope blob size and the successor-cache fan-out — from an
  unbounded input list. A generous ceiling; real reasoning steps use a handful."
  50)

(defn add-declared
  "Add TNLR `t` to the declared inputs (dedup by TNLR). Returns the new declarations."
  [tnlrs t]
  (conj (filterv #(not (same-tnlr? % t)) tnlrs) (tnlr t)))

(defn drop-declared
  "Remove the declared input on TNLR `t`."
  [tnlrs t]
  (filterv #(not (same-tnlr? % t)) tnlrs))

(defn pin-all
  "Resolve every declared TNLR to its current latest id → {tnlr-key → id}. `latest-of`
  maps a TNLR → id."
  [tnlrs latest-of]
  (into {} (map (fn [t] [(tnlr-key t) (latest-of t)])) tnlrs))

(defn repin
  "Point the pin for TNLR `t` at `new-id` (used when `t` gets a new minor)."
  [pins t new-id]
  (assoc pins (tnlr-key t) new-id))

(defn input-refs
  "The API-facing input refs — each declared TNLR plus its pinned id."
  [tnlrs pins]
  (mapv (fn [t] (assoc t :id (get pins (tnlr-key t)))) tnlrs))

;; --- successor index ---------------------------------------------------------

(defn successor-tuples
  "The reverse-edge rows for `successor-id`: one {:tnlr … :successor-id …} per declared
  input TNLR."
  [successor-id tnlrs]
  (mapv (fn [t]
          {:tnlr t
           :successor-id successor-id})
        tnlrs))
