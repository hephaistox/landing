(ns landing.agora.document.kind
  "The Agora document **register** of `kind`
  it's an epistemic/rhetorical family and the capabilities that follow from it)."
  (:require
   [clojure.string :as str]))

(def kinds
  "For a ki, the document `kind`s is deduction / induction / prediction / definition / belief / assumption, plus the bibliographic `work` and `extract`);
  For an *article*, kind sit on a rhetorical axis (*what is this prose doing?* — `explainer` `evangelism`).

  Each kind declares its **capabilities and presentation as data**, so
  consumers (backend + UI) read a field rather than branching on a specific kind:
   - `:object-type` — which document type carries it (`:ki` / `:article`, the same keyword
     values as `identity/object-types`); drives the per-type kind enum and selector
     (`kind-ids-of`).
   - `:color`  — accent colour.
   - `:inputs?` — **may a document of this kind take inputs** (in-text `[[ki:…]]` citations)? A
     `work` is a leaf and takes none; everything else does (an `extract` cites the one `work` it
     draws from). This one flag drives both the backend (input derivation) and the UI (whether the
     citation feature is shown).
   - `:requires-inputs?` — **must** a document of this kind derive from at least one input? True for
     the reasoning kinds (deduction / induction / prediction / counter-example / illustration) whose claim stands
     on another claim; a foundation (belief / assumption / measurable-fact), a definition or a
     bibliographic leaf (`work`) has none required. Drives the `missing-inputs` document error.
   - `:def-name` + `:def-major` — a pointer to the KI that *defines* the kind. The rest of that
     identity is implied — `type` is `ki`, `minor` resolves to the latest, `lang` is the
     reader's — so only name + major are declared here (see `kind-def`).
   - `:say` — the statement scaffold `{:subject :author|:term, :phrase {lang → connector}}` that
     opens the kind's sentence (\"<author> believes that …\", \"<term> designates …\"). Absent for
     free-form kinds (`deduction`); drives `statement-prefix` (see `statement-say`).
   - `:consequence` — the default kind of a new consequence built on this one (`add consequence`);
     absent means `deduction` (see `kind-consequence`).
  The set is NOT enforced by the DB; the API validates against it and the UI renders it."
  [{:id :deduction
    :color "#2c5aa0"
    :inputs? true
    :requires-inputs? true
    :object-type :ki
    :def-name "type-deduction"
    :def-major 1}
   {:id :induction
    :color "#2e8b8b"
    :inputs? true
    :requires-inputs? true
    :object-type :ki
    :def-name "type-induction"
    :def-major 1}
   {:id :prediction
    :color "#0b7285"
    :inputs? true
    :requires-inputs? true
    :object-type :ki
    :def-name "type-prediction"
    :def-major 1
    :say {:subject :author
          :phrase {:en "predicts that"
                   :fr "prédit que"}}}
   {:id :measurable-fact
    :color "#087f5b"
    :inputs? true
    :object-type :ki
    :def-name "type-measurable-fact"
    :def-major 1}
   {:id :definition
    :color "#a61e8c"
    :inputs? true
    :object-type :ki
    :def-name "type-definition"
    :def-major 1
    :say {:subject :term
          :phrase {:en "designates"
                   :fr "désigne"}}}
   {:id :belief
    :color "#2b8a3e"
    :inputs? true
    :object-type :ki
    :def-name "type-belief"
    :def-major 1
    :say {:subject :author
          :phrase {:en "believes that"
                   :fr "croit que"}}}
   {:id :assumption
    :color "#e8590c"
    :inputs? true
    :object-type :ki
    :def-name "type-assumption"
    :def-major 1
    :say {:subject :author
          :phrase {:en "assumes that"
                   :fr "suppose que"}}}
   {:id :illustration
    :color "#1098ad"
    :inputs? true
    :requires-inputs? true
    :object-type :ki
    :def-name "type-illustration"
    :def-major 1}
   {:id :counter-example
    :color "#e03131"
    :inputs? true
    :requires-inputs? true
    :object-type :ki
    :def-name "type-counter-example"
    :def-major 1}
   {:id :extract
    :color "#495057"
    :inputs? true
    :bibliographic? true
    :consequence :definition
    :object-type :ki
    :def-name "type-extract"
    :def-major 1}
   {:id :work
    :color "#846358"
    :inputs? false
    :bibliographic? true
    :consequence :extract
    :object-type :ki
    :def-name "type-work"
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
  default** (deduction for KIs, explainer for articles)."
  [object-type]
  (mapv :id (kinds-of object-type)))

(def ^:private kind->color
  "kind (keyword) → accent colour."
  (into {} (map (juxt :id :color)) kinds))

(defn kind-color
  "Accent colour for `kind` (a keyword), or `default`."
  ([kind] (kind-color kind nil))
  ([kind default]
   (get kind->color
        (some-> kind
                keyword)
        default)))

(def ^:private kind-inputs?
  "kind (keyword) → whether a KI of that kind may take inputs."
  (into {} (map (juxt :id :inputs?)) kinds))

(defn kind-allows-inputs?
  "May a document of `kind` (a keyword) have inputs (in-text
  `[[ki:…]]` citations)?"
  [kind]
  (not (false? (get kind-inputs?
                    (some-> kind
                            keyword)))))

(def ^:private kind-requires-inputs?-map
  "kind (keyword) → whether a document of that kind must derive from at least one input."
  (into {} (map (juxt :id :requires-inputs?)) kinds))

(defn kind-requires-inputs?
  "Must a document of `kind` (a keyword) derive from at least one input (predecessor)? True for the
  reasoning kinds; false for foundations, definitions and bibliographic leaves. A `kind` with this
  requirement and no input is a `missing-inputs` document error."
  [kind]
  (boolean (get kind-requires-inputs?-map
                (some-> kind
                        keyword))))

(def ^:private kind-bibliographic?-map
  "kind (keyword) → whether it is a bibliographic kind (`work` / `extract`)."
  (into {} (map (juxt :id :bibliographic?)) kinds))

(defn kind-bibliographic?
  "Is `kind` (a keyword) a bibliographic kind — a `work` or an `extract`? These are authored through
  their own bibliographic surfaces (a work form, an extract's work-picker + locator), so the generic
  quick-create-a-predecessor form excludes them."
  [kind]
  (boolean (get kind-bibliographic?-map
                (some-> kind
                        keyword))))

(def ^:private kind-consequence-map
  "kind (keyword) → the default kind of a consequence built on it, for kinds that declare one."
  (into {} (keep (fn [{:keys [id consequence]}] (when consequence [id consequence]))) kinds))

(defn kind-consequence
  "The default kind for a **consequence** (a new successor citing this one) of a document of `kind` —
  what `add consequence` creates. A `work` yields an `extract` (a verbatim passage of it), an `extract`
  an appropriation (`definition`), everything else a `deduction` (the default reasoning step). It is a
  default, not a rule: the author may pick another kind — e.g. a whole-work claim citing a work
  directly (\"the Bible is not set in the 21st century\")."
  [kind]
  (get kind-consequence-map
       (some-> kind
               keyword)
       :deduction))

(def kind-def
  "kind (keyword) → the identity of the KI that defines it: `{:type :name :major}`.
  `type` is always `ki` and `minor` is omitted (it resolves to the latest via by-major);
  the caller supplies the reader's `:lang`. This is the single source of the kind ↔
  definition-KI link — the badge (frontend) and the type seed (backend) both read it, so
  the `type-<kind>` slug convention lives in exactly one place."
  (into {}
        (map (juxt :id
                   (fn [{:keys [def-name def-major]}]
                     {:type :ki
                      :name def-name
                      :major def-major})))
        kinds))

;; --- Kind-guided statement scaffold ---------------------------------------------------
;; Each epistemic kind (except the open `deduction`/`induction`) scaffolds the opening of the statement,
;; so the kind is enforced by the grammar rather than being a decorative badge. Two shapes:
;;   - author-attributed (belief/assumption/prediction): "<author> <verb> that "
;;   - term contract (definition): "<term> means "
;; `deduction`/`induction` have no scaffold (free-form). Only the author's **body** is stored in `:text`;
;; the prefix is DERIVED here (shared clj + cljs) so it stays correct as kind/author change
;; and the SPA can render it instantly, without waiting on the read endpoint.

(def ^:private statement-say
  "kind (keyword) → its `:say` scaffold `{:subject :author|:term, :phrase {lang → connector}}`, read
  straight from `kinds`. Absent for free-form kinds (`deduction`) which declare no `:say`."
  (into {} (keep (fn [{:keys [id say]}] (when say [id say])) kinds)))

(defn statement-subject-kind
  "Whether `kind`'s (keyword or string) prefix subject is the `:author` or the `:term`, or nil for a
  free-form kind (`deduction`)."
  [kind]
  (:subject (get statement-say
                 (some-> kind
                         keyword))))

(defn- cap-first
  [s]
  (let [s (str s)] (if (str/blank? s) s (str (str/upper-case (subs s 0 1)) (subs s 1)))))

(defn statement-prefix
  "The kind-guided opening (with a trailing space) for a statement in `lang`, or nil for a
  free-form kind. `subject` is the attributed author (author-kinds) or the defined term
  (definition). E.g. (\"belief\" \"en\" \"Sun Tzŭ\") → \"Sun Tzŭ believes that \"."
  [kind lang subject]
  (when-let [{:keys [phrase]} (get statement-say
                                   (some-> kind
                                           keyword))]
    (when-let [connector (or (get phrase lang) (get phrase :en))]
      (when-not (str/blank? subject) (str (cap-first subject) " " connector " ")))))

(defn attributed-author
  "The person a statement is attributed to: the document's own author (`:author`). For most documents
  this is the contributor who wrote it — an `extract` included, it belongs to its writer. A `work` is
  the one exception: its `:author` is the cited author (Sun Tzŭ), not the contributor who added the
  record. The work an extract draws from is shown alongside it, never as the extract's byline."
  [doc]
  (:author doc))

(defn attributed-author-id
  "The id of the person `attributed-author` names — for linking the byline to their profile hub. A
  `work` carries its cited author as `:author-id`, distinct from `:owner-id` (the contributor who
  maintains the record); every other document has no `:author-id`, so the byline links to its
  `:owner-id`. `:owner-id` stays the accountability/permissions concept; this is display only."
  [doc]
  (or (:author-id doc) (:owner-id doc)))

(defn extract-prefix-parts
  "The pieces of an `extract`'s opening sentence in `lang`, from its resolved `work`
  (`{:title :author-name :author-id :locator}`): `{:before :author-name :author-id :after}`, where
  `before`/`after` bracket the cited author. A renderer joins them for plain text (`extract-prefix`)
  or renders the author as a profile link between them (read page). nil when there is no work (e.g.
  the live editor, which holds no resolved work)."
  [{:keys [title author-name author-id locator]} lang]
  (when-not (str/blank? title)
    (let [loc (when-not (str/blank? locator) (str " (" locator ")"))]
      (if (= lang :en)
        {:before (str "In “" title "”, ")
         :author-name author-name
         :author-id author-id
         :after (str " writes" loc ": ")}
        {:before (str "Dans « " title " », ")
         :author-name author-name
         :author-id author-id
         :after (str " écrit" loc " : ")}))))

(defn- extract-prefix
  "The plain-text opening of an `extract` in `lang` (author as text) — cards and SSR. The read page
  uses `extract-prefix-parts` to make the author a link."
  [work lang]
  (when-let [{:keys [before author-name after]} (extract-prefix-parts work lang)]
    (str before author-name after)))

(defn- doc-author
  "The person a statement is attributed to, from either the raw doc (`:author`) or the endpoint view
  (`:attributed-author`, since the view drops the raw field). Lets the prefix render the byline on
  both sides without the caller reshaping the doc."
  [doc]
  (or (:attributed-author doc) (:author doc)))

(defn- definition-prefix
  "The opening of a `definition` in `lang`: the author appropriates the term for the graph — « pour
  <author>, dans ce contexte, ‹ <term> › désigne ». The connector comes from the definition `:say`
  (single source); the author clause is dropped when the author is unknown; nil without a term."
  [author term lang]
  (when-not (str/blank? term)
    (when-let [connector (or (get-in statement-say [:definition :phrase lang])
                             (get-in statement-say [:definition :phrase :en]))]
      (let [who (when-not (str/blank? author)
                  (if (= lang :en) (str "For " author ", ") (str "Pour " author ", ")))]
        (if (= lang :en)
          (str who "in this context, “" term "” " connector " ")
          (str who "dans ce contexte, « " term " » " connector " "))))))

(defn- instance-prefix
  "The opening of an example (`:illustrate`) or a counter-example (`:refute`) in `lang`: the author
  brings the instance (`title`) to bear on the claim it references (`target`, resolved with a `:title`)
  — « D'après <author>, « <title> > <verbe> « <target> » : ». The author clause is dropped when unknown;
  nil without a resolved target."
  [author title target lang relation]
  (when-let [tgt (:title target)]
    (let [who (cond
                (str/blank? author) ""
                (= lang :en) (str "According to " author ", ")
                :else (str "D'après " author ", "))
          verb (case [relation lang]
                 [:illustrate :en] "illustrates"
                 [:illustrate :fr] "illustre"
                 [:refute :en] "refutes"
                 [:refute :fr] "réfute")]
      (str who "« " title " » " verb " « " tgt " » : "))))

(defn statement-prefix-of
  "The kind-guided opening for `doc` in `lang` (its subject resolved from the doc), or nil for a
  free-form kind. An `extract` opens with its cited work + locator (`extract-prefix`, from the resolved
  `:work`); a `definition` with its author appropriating the term (`definition-prefix`); an
  `illustration` / `counter-example` with the instance brought to bear on the claim it references
  (`instance-prefix`, from the resolved `:target`). Rendered *separately* from the body so the
  citation-parsed prose can follow a plain-text prefix (read page, editor label)."
  [doc lang]
  (case (some-> (:kind doc)
                keyword)
    :extract (extract-prefix (:work doc) lang)
    :definition (definition-prefix (doc-author doc) (:title doc) lang)
    :illustration (instance-prefix (doc-author doc) (:title doc) (:target doc) lang :illustrate)
    :counter-example (instance-prefix (doc-author doc) (:title doc) (:target doc) lang :refute)
    (let [kind (:kind doc)
          subject (case (statement-subject-kind kind)
                    :author (doc-author doc)
                    :term (:title doc)
                    nil)]
      (statement-prefix kind lang subject))))

(defn compose-statement
  "The full statement sentence for `doc` in `lang`: the kind-guided prefix + the authored
  `body`. Author-kinds prepend `<attributed-author> <verb> that `; `definition` prepends
  `<title> designates `; free-form kinds (`deduction`) return the body unchanged."
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
