(ns landing.agora.document.kind
  "The Agora document **register** of `kind` (it's an epistemic/rhetorical family and the
  capabilities that follow from it)."
  (:require
   [clojure.string :as str]))

(def kinds
  "For a ki, the document `kind`s is inference / prediction / definition / belief / assumption, plus `source`);
  For an *article*, kind sit on a rhetorical axis (*what is this prose doing?* — `explainer` `evangelism`).

  Each kind declares its **capabilities and presentation as data**, so
  consumers (backend + UI) read a field rather than branching on a specific kind:
   - `:object-type` — which document type carries it (`:ki` / `:article`, the same keyword
     values as `identity/object-types`); drives the per-type kind enum and selector
     (`kind-ids-of`).
   - `:color`  — accent colour.
   - `:inputs?` — **may a document of this kind take inputs** (in-text `[[ki:…]]` citations)? A
     `source` is a leaf work and takes none; everything else does. This one flag drives both
     the backend (input derivation) and the UI (whether the quotation feature is shown).
   - `:def-name` + `:def-major` — a pointer to the KI that *defines* the kind. The rest of that
     identity is implied — `type` is `ki`, `minor` resolves to the latest, `lang` is the
     reader's — so only name + major are declared here (see `kind-def`).
  The set is NOT enforced by the DB; the API validates against it and the UI renders it."
  [{:id :inference
    :color "#2c5aa0"
    :inputs? true
    :object-type :ki
    :def-name "type-inference"
    :def-major 1}
   {:id :prediction
    :color "#0b7285"
    :inputs? true
    :object-type :ki
    :def-name "type-prediction"
    :def-major 1}
   {:id :definition
    :color "#a61e8c"
    :inputs? true
    :object-type :ki
    :def-name "type-definition"
    :def-major 1}
   {:id :belief
    :color "#2b8a3e"
    :inputs? true
    :object-type :ki
    :def-name "type-belief"
    :def-major 1}
   {:id :assumption
    :color "#e8590c"
    :inputs? true
    :object-type :ki
    :def-name "type-assumption"
    :def-major 1}
   {:id :illustration
    :color "#1098ad"
    :inputs? true
    :object-type :ki
    :def-name "type-illustration"
    :def-major 1}
   {:id :counter-example
    :color "#e03131"
    :inputs? true
    :object-type :ki
    :def-name "type-counter-example"
    :def-major 1}
   {:id :source
    :color "#495057"
    :inputs? false
    :in-text? false
    :object-type :ki
    :def-name "type-source"
    :def-major 1}
   {:id :explainer
    :color "#6741d9"
    :inputs? true
    :object-type :article
    :def-name "type-explainer"
    :def-major 1}])

(def kind-ids
  "All kind ids (keywords), in display order, across every object type."
  (mapv :id kinds))

(defn kinds-of
  "The kinds declared for object type `object-type` (`:ki` / `:article`), in display order.
  Coerces its argument to a keyword, so a string type from the DB/API boundary (`\"ki\"`) is
  accepted just as the keyword is."
  [object-type]
  (let [ot (keyword object-type)] (filterv #(= ot (:object-type %)) kinds)))

(defn kind-ids-of
  "The kind ids (keywords) for `object-type`, in display order; the **first is that type's
  default** (inference for KIs, explainer for articles)."
  [object-type]
  (mapv :id (kinds-of object-type)))

(def kind-color
  "kind name (string) → accent colour."
  (into {} (map (juxt (comp name :id) :color)) kinds))

(def ^:private kind-inputs?
  "kind name (string) → whether a KI of that kind may take inputs."
  (into {} (map (juxt (comp name :id) :inputs?)) kinds))

(defn kind-allows-inputs?
  "May a document of `kind` have inputs (in-text `[[ki:…]]` citations)?"
  [kind]
  (not (false? (get kind-inputs? kind))))

(def ^:private kind-in-text?
  "kind name (string) → whether *quoting* a KI of that kind writes the citation into the prose."
  (into {} (map (juxt (comp name :id) :in-text?)) kinds))

(defn kind-quotes-in-text?
  "When a KI quotes a document of `kind`, is the citation written **into the prose** (an inline
  `[[ki:…]]` token, the default) or recorded as an **input edge only**?"
  [kind]
  (not (false? (get kind-in-text? kind))))

(def kind-def
  "kind name (string) → the identity of the KI that defines it: `{:type :name :major}`.
  `type` is always `ki` and `minor` is omitted (it resolves to the latest via by-major);
  the caller supplies the reader's `:lang`. This is the single source of the kind ↔
  definition-KI link — the badge (frontend) and the type seed (backend) both read it, so
  the `type-<kind>` slug convention lives in exactly one place."
  (into {}
        (map (juxt (comp name :id)
                   (fn [{:keys [def-name def-major]}]
                     {:type :ki
                      :name def-name
                      :major def-major})))
        kinds))

;; --- Kind-guided statement scaffold ---------------------------------------------------
;; Each epistemic kind (except the open `inference`) scaffolds the opening of the statement,
;; so the kind is enforced by the grammar rather than being a decorative badge. Two shapes:
;;   - author-attributed (belief/assumption/prediction): "<author> <verb> that "
;;   - term contract (definition): "<term> means "
;; `inference` has no scaffold (free-form). Only the author's **body** is stored in `:text`;
;; the prefix is DERIVED here (shared clj + cljs) so it stays correct as kind/author change
;; and the SPA can render it instantly, without waiting on the read endpoint.

(def statement-say
  "kind (string) → {:subject :author|:term, :phrase {lang → connector}} — the scaffold for
  that kind's statement opening. Absent for the open `inference` (free-form)."
  {"belief" {:subject :author
             :phrase {:en "believes that"
                      :fr "croit que"}}
   "assumption" {:subject :author
                 :phrase {:en "assumes that"
                          :fr "suppose que"}}
   "prediction" {:subject :author
                 :phrase {:en "predicts that"
                          :fr "prédit que"}}
   "definition" {:subject :term
                 :phrase {:en "designates"
                          :fr "désigne"}}})

(defn statement-subject-kind
  "Whether `kind`'s prefix subject is the `:author` or the `:term`, or nil for a free-form
  kind (`inference`)."
  [kind]
  (:subject (get statement-say kind)))

(defn- cap-first
  [s]
  (let [s (str s)] (if (str/blank? s) s (str (str/upper-case (subs s 0 1)) (subs s 1)))))

(defn statement-prefix
  "The kind-guided opening (with a trailing space) for a statement in `lang`, or nil for a
  free-form kind. `subject` is the attributed author (author-kinds) or the defined term
  (definition). E.g. (\"belief\" \"en\" \"Sun Tzŭ\") → \"Sun Tzŭ believes that \"."
  [kind lang subject]
  (when-let [{:keys [phrase]} (get statement-say kind)]
    (when-let [connector (or (get phrase lang) (get phrase :en))]
      (when-not (str/blank? subject) (str (cap-first subject) " " connector " ")))))

(defn attributed-author
  "The person a statement is attributed to, in priority order:
   1. `:source`'s author — for a `kind=source` KI itself (its `:source` resolves to the work);
   2. `:quote-author-name` — for a KI that **quotes a source** (the work-author of the source-KI
      it inputs), computed by the read layer from the inputs;
   3. else the document's own author.
  So \"Led Zeppelin est le meilleur groupe de rock\" (a position quoting a Rolling Stone source
  by David Fricke) reads \"David Fricke soutient que …\"."
  [doc]
  (or (:author-name (:source doc)) (:quote-author-name doc) (:author doc)))

(defn statement-prefix-of
  "The kind-guided opening for `doc` in `lang` (its subject resolved from the doc), or nil
  for a free-form kind. Rendered *separately* from the body so the citation-parsed prose can
  follow a plain-text prefix (read page, editor label)."
  [doc lang]
  (let [kind (:kind doc)
        subject (case (statement-subject-kind kind)
                  :author (attributed-author doc)
                  :term (:title doc)
                  nil)]
    (statement-prefix kind lang subject)))

(defn compose-statement
  "The full statement sentence for `doc` in `lang`: the kind-guided prefix + the authored
  `body`. Author-kinds prepend `<attributed-author> <verb> that `; `definition` prepends
  `<title> designates `; free-form kinds (`inference`) return the body unchanged."
  [doc lang body]
  (str (statement-prefix-of doc lang) body))

;; --- prose block structure (shared so the SPA + SSR renderers never drift) ---------
(def ^:private bullet-pattern
  "A line that is a bullet item: a `-` or `*` marker followed by whitespace (optionally
  indented). The trailing whitespace requirement means a bare `-` is prose, not a bullet."
  #"^[ \t]*[-*][ \t]+")

(defn parse-blocks
  "Structure prose `text` into renderable blocks so the SPA and the server-rendered body
  agree:
   - `{:type :ul :items [line …]}` — a run of bullet lines (`- ` / `* `), markers stripped;
   - `{:type :p  :lines [line …]}` — a run of other non-blank lines. A single line-break is a
     new line within the paragraph (render as `<br>`); a **blank line separates blocks**.
  Inline `[[ki:…]]` citations are left intact for the caller to resolve."
  [text]
  (let [class (fn [l]
                (cond
                  (str/blank? l) :blank
                  (re-find bullet-pattern l) :ul
                  :else :p))]
    (->> (str/split (or text "") #"\n")
         (partition-by class)
         (keep (fn [grp]
                 (case (class (first grp))
                   :blank nil
                   :ul {:type :ul
                        :items (mapv #(str/replace % bullet-pattern "") grp)}
                   :p {:type :p
                       :lines (vec grp)})))
         vec)))
