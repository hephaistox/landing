(ns agora.cli
  "Agora from the command line: the actions of the domain, on a repository kept in an EDN
  file.

  `bb agora` opens a menu: who acts, which action, then each parameter, picked from lists
  (fzf) or typed. Ids and dates are made here, as a backend would. The action runs on the
  repository, its changes are applied and saved, or its refusal is shown with its reason.

  `bb agora help` lists the commands."
  (:require
   [agora.entity.objection   :as objection]
   [agora.entity.publication :as publication]
   [agora.entity.user        :as user]
   [agora.repository         :as repository]
   [agora.value.name         :as name]
   [agora.value.type         :as type]
   [babashka.fs              :as fs]
   [babashka.process         :as p]
   [bling.core               :as b]
   [clojure.edn              :as edn]
   [clojure.java.io          :as io]
   [clojure.pprint           :as pprint]
   [clojure.string           :as str]
   [clojure.walk             :as walk]))

;; ---------------------------------------------------------------------------
;; Repository and session
;; ---------------------------------------------------------------------------

(def ^:dynamic *repo-file* "cli-data/agora.edn")

(defn- session-file [] (str *repo-file* ".session"))

(defn load-repo
  []
  (if (fs/exists? *repo-file*)
    (merge repository/empty-repository (edn/read-string (slurp *repo-file*)))
    repository/empty-repository))

(defn save-repo!
  [repo]
  (fs/create-dirs (or (fs/parent *repo-file*) "."))
  (spit *repo-file* (with-out-str (pprint/pprint repo))))

(defn load-by
  "Who acts: `:anonymous`, or `{:id …}` once logged in."
  []
  (if (fs/exists? (session-file)) (edn/read-string (slurp (session-file))) :anonymous))

(defn save-by!
  [by]
  (fs/create-dirs (or (fs/parent (session-file)) "."))
  (spit (session-file) (pr-str by)))

;; ---------------------------------------------------------------------------
;; Display
;; ---------------------------------------------------------------------------

(defn- person-name
  [repo {:keys [id]}]
  (or (some #(when (= id (:id %)) (:name %)) (:persons repo)) (str id)))

(defn- version-label
  [d]
  (str (name (:type d))
       " "
       (:name d)
       " "
       (name (:lang d))
       "@"
       (:major d)
       (when (:minor d) (str "." (:minor d)))))

(defn- who [repo by] (if (map? by) (person-name repo by) "nobody"))

(defn- section [title] (println) (println (b/bling [:bold.blue title])))

(declare label)

(defn- badge
  "A status, colored."
  [status]
  (let [color (case status
                (:open :draft :pending) "green"
                (:closed :complete) "gray"
                :canceled "red"
                "orange")]
    (b/bling [(keyword (str "bold." color)) (format "%-8s" (name status))])))

(defn- clip
  [n s]
  (let [s (str/replace (str s) #"\s+" " ")] (if (> (count s) n) (str (subs s 0 (dec n)) "…") s)))

(defn- publication-line
  [repo p]
  (b/bling [:bold (format "%-32s" (str (:name p) " " (name (:lang p)) "@" (:major p)))]
           "  "
           (badge (:status p))
           [:gray
            (format "%-34s"
                    (str "  " (person-name repo (:owner p))
                         " · " (count (:documents p))
                         " doc." (when (seq (:answers p))
                                   (str " · answers " (count (:answers p))))))]
           "  "
           (clip 50
                 (-> p
                     :content
                     :title))))

(defn- objection-line
  [repo o]
  (b/bling [:bold (format "%-32s" (str (:name o) " " (name (:lang o)) "@" (:major o)))]
           "  "
           (badge (:status o))
           [:gray
            (format "%-34s"
                    (str "  " (person-name repo (:owner o))
                         " · " (name (-> o
                                         :content
                                         :reason))))]
           "  "
           (clip 50
                 (-> o
                     :content
                     :title))))

(defn show
  "Prints a summary of the repository: counts, one line per publication and objection."
  [repo]
  (let [freq (fn [xs]
               (str/join ", "
                         (for [[k n] (sort-by key (frequencies (map :status xs)))]
                           (str n " " (name k)))))]
    (println (b/bling [:gray
                       (str (count (:users repo))
                            " users · "
                            (count (:publications repo))
                            " publications ("
                            (freq (:publications repo))
                            ") · "
                            (count (publication/documents (:publications repo)))
                            " documents · "
                            (count (:objections repo))
                            " objections ("
                            (freq (:objections repo))
                            ")")]))
    (section "Publications")
    (doseq [p (:publications repo)] (println " " (publication-line repo p)))
    (section "Objections")
    (if (seq (:objections repo))
      (doseq [o (:objections repo)] (println " " (objection-line repo o)))
      (println (b/bling [:italic.gray "  none"])))))

(defn- citation-label
  [repo c]
  (if (:id c)
    (str "draft "
         (or (some #(when (= (:id c) (:id %)) (version-label %))
                   (publication/documents (:publications repo)))
             (:id c)))
    (str (name (:type c)) " " (:name c) " " (name (:lang c)) "@" (:major c))))

(defn- document-detail
  [repo d]
  (println (b/bling "  "
                    [:bold.orange (version-label d)]
                    (when-not (:minor d) [:green "  draft"])
                    [:gray (str "  " (person-name repo (:owner d)))]
                    (when-let [t (:timestamp d)] (b/bling "  ⏱ " (badge (:status t))))))
  (println (b/bling "    "
                    [:bold
                     (-> d
                         :content
                         :title)]))
  (when-let [t (-> d
                   :content
                   :text)]
    (println (b/bling "    " [:italic (clip 100 t)])))
  (doseq [c (-> d
                :content
                :inputs)]
    (println (b/bling [:gray "    ↳ cites "] (citation-label repo c)))))

(defn- find-entity
  "The publication, objection or user `key` designates: its id, or its name."
  [repo key]
  (let [id (parse-uuid (str key))
        hit? #(or (= id (:id %)) (= key (:name %)))]
    (or (some #(when (hit? %) [:publication %]) (:publications repo))
        (some #(when (= id (:id %)) [:document %]) (publication/documents (:publications repo)))
        (some #(when (hit? %) [:objection %]) (:objections repo))
        (some #(when (hit? %) [:user %]) (:persons repo)))))

(defn detail
  "Prints the detail of the publication, objection or user `key` designates."
  [repo key]
  (let [[kind x] (find-entity repo key)]
    (case kind
      :publication (do (println (publication-line repo x))
                       (println (b/bling [:gray
                                          (str "  "
                                               (-> x
                                                   :content
                                                   :text))]))
                       (when (seq (:contributors x))
                         (println (b/bling
                                   [:gray "  contributors: "]
                                   (str/join ", " (map #(person-name repo %) (:contributors x))))))
                       (doseq [o (:answers x)]
                         (println (b/bling [:gray "  answers "]
                                           (some #(when (= (:id o) (:id %)) (objection-line repo %))
                                                 (:objections repo)))))
                       (println)
                       (doseq [d (:documents x)] (document-detail repo d)))
      :document (let [p (publication/from-publication (:publications repo) x)]
                  (document-detail repo x)
                  (println)
                  (println (b/bling [:gray "  in "] (publication-line repo p)))
                  (doseq [o (:objections repo)
                          :when (= [{:id (:id x)}] (:documents o))]
                    (println (b/bling [:gray "  contested by "] (objection-line repo o)))))
      :objection
      (let [target (some #(when (= (:id (first (:documents x))) (:id %)) %)
                         (publication/documents (:publications repo)))]
        (println (objection-line repo x))
        (println (b/bling [:gray "  contests "] (if target (label repo target) "?")))
        (println (b/bling "  "
                          [:italic
                           (-> x
                               :content
                               :text)]))
        (doseq [c (-> x
                      :content
                      :inputs)]
          (println (b/bling [:gray "  ↳ backed by "] (citation-label repo c))))
        (doseq [o (sort-by :major (:objections repo))
                :when (and (= (:name o) (:name x)) (= (:lang o) (:lang x)) (not= (:id o) (:id x)))]
          (println (b/bling [:gray "  other major "] (objection-line repo o)))))
      :user
      (let [mine #(= {:id (:id x)} (:owner %))]
        (println (b/bling [:bold (:name x)]
                          [:gray
                           (if (some #(= (:id x) (:id %)) (:users repo))
                             "  user"
                             "  person without an account")]))
        (doseq [p (:publications repo) :when (mine p)] (println "  " (publication-line repo p)))
        (doseq [o (:objections repo) :when (mine o)] (println "  " (objection-line repo o))))
      (println (b/bling [:red "nothing named "] (str key))))))

(defn- label
  "A one-line label of a repository entity."
  [repo x]
  (cond
    (= :publication (:type x)) (str (:name x)
                                    " " (name (:lang x))
                                    "@" (:major x)
                                    " — " (name (:status x))
                                    " — " (-> x
                                              :content
                                              :title))
    (= :objection (:type x)) (str (:name x)
                                  " " (name (:lang x))
                                  "@" (:major x)
                                  " — " (name (:status x))
                                  " — " (-> x
                                            :content
                                            :title))
    (:type x) (str (version-label x)
                   " — "
                   (-> x
                       :content
                       :title))
    :else (person-name repo x)))

(defn- show-changes
  [repo changes]
  (doseq [[verb k] [["created" :created] ["changed" :changed] ["deleted" :deleted]]
          [kind xs] (get changes k)
          x xs]
    (println
     (b/bling [:bold.green "  ✔ "] [:green verb] " " [:gray (name kind)] "  " (label repo x)))))

(defn- refusal-explanation
  "The item of the docstring of `action-var` explaining `reason`, or nil."
  [action-var reason]
  (some->> (:doc (meta action-var))
           (#(str/split % #"\n\s*-\s+"))
           (map #(str/join " " (map str/trim (str/split-lines %))))
           (some #(when (str/includes? % (str "`" reason "`"))
                    (-> %
                        (str/replace #"^\s*-\s*" "")
                        (str/replace #"^(`[^`]+`,?\s*)+:\s*" "")
                        (str/replace #";$|\.$" "")
                        str/trim)))))

;; ---------------------------------------------------------------------------
;; Picking and typing
;; ---------------------------------------------------------------------------

(defn fzf
  "The line picked among `choices` with fzf, or nil when nothing is picked."
  [prompt choices & [preview]]
  (let [{:keys [out exit]}
        @(p/process (cond-> ["fzf" "--height=40%" "--reverse" "--prompt" (str prompt " > ")]
                      preview (into ["--preview" preview "--preview-window=right:60%:wrap"]))
                    {:in (str/join "\n" choices)
                     :out :string
                     :err :inherit})]
    (when (zero? exit) (str/trim out))))

(defn pick
  "The element of `xs` picked by its `label-fn`."
  [prompt xs label-fn]
  (when (seq xs)
    (let [labels (mapv label-fn xs)
          chosen (fzf prompt labels)]
      (some (fn [[l x]] (when (= l chosen) x)) (map vector labels xs)))))

(def ^:dynamic *subject*
  "What the questions asked now are about, e.g. \"publication\"; nil when obvious."
  nil)

(defn ask
  "A line typed after `prompt`, named after `*subject*`; `default` when the line is empty."
  [prompt & [default]]
  (print (b/bling [:bold.blue (str "  " (when *subject* (str *subject* " ")) prompt)]
                  (when default [:gray (str " [" default "]")])
                  ": "))
  (flush)
  (let [line (str/trim (or (read-line) ""))] (if (and (str/blank? line) default) default line)))

(defn yes? [prompt] (= "yes" (fzf prompt ["no" "yes"])))

(defn- doc-by-id
  [repo id]
  (some #(when (= id (:id %)) %) (publication/documents (:publications repo))))

(defn- pub-of [repo d] (publication/from-publication (:publications repo) d))

(defn- obj-by-id [repo id] (some #(when (= id (:id %)) %) (:objections repo)))

(defn- pub-by-id [repo id] (some #(when (= id (:id %)) %) (:publications repo)))

;; ---------------------------------------------------------------------------
;; Parameters, from the grammar of an action and what the screen knows
;; ---------------------------------------------------------------------------

;; `ctx` carries what is known while the parameters are asked: who acts (`::by`), the
;; action (`::action`), the values the screen fixed (`::fixed`), the version an objection
;; contests (`::target`), the reason picked (`::reason`). Every pick offers only what makes
;; sense there; a single candidate is taken without asking.

(defn- children
  "`[key props schema]` of a `[:map …]` grammar."
  [grammar]
  (for [c (rest grammar) :when (vector? c)] (if (map? (second c)) c [(first c) {} (second c)])))

(defn- ref?
  [schema]
  (and (vector? schema) (= :map (first schema)) (= [:id] (map first (children schema)))))

(defn- published-versions
  [repo]
  (filter :minor (publication/documents (filter publication/closed? (:publications repo)))))

(defn- drafts [repo] (remove :minor (publication/documents (:publications repo))))

(defn- writes-in?
  "Does `by` write in the publication `p`, open, as its owner or a contributor?"
  [by p]
  (and (publication/open? p) (some #{by} (cons (:owner p) (:contributors p)))))

(defn- lineage-of
  [repo d]
  (filter #(= ((juxt :type :name :lang) d) ((juxt :type :name :lang) %))
          (publication/documents (:publications repo))))

(defn- holds-change?
  "Does the publication `p` hold a later version of `target`?"
  [p target]
  (some #(objection/newer? target %) (:documents p)))

(defn- target-of
  "The version a picked `:version` designates, or the one an `:objection` contests."
  [repo k v]
  (let [docs (publication/documents (:publications repo))
        by-id (fn [id] (some #(when (= id (:id %)) %) docs))]
    (case k
      :version (by-id (:id v))
      :objection (some->> (:objections repo)
                          (some #(when (= (:id v) (:id %)) %))
                          :documents first
                          :id by-id)
      nil)))

(defn- latest-target
  "The version the latest major of the objection `o` contests."
  [repo o]
  (let [majors (filter #(= ((juxt :name :lang) o) ((juxt :name :lang) %)) (:objections repo))]
    (target-of repo :objection (apply max-key :major majors))))

(defn- candidates
  "`[entities prompt]` that `k` may designate, given `ctx`."
  [repo ctx k]
  (let [{by ::by
         action ::action
         fixed ::fixed
         target ::target}
        ctx
        me? (map? by)
        pub (some->> fixed
                     :publication
                     :id
                     (pub-by-id repo))]
    (case k
      :publication (let [mine (cond->> (if me?
                                         (filter #(writes-in? by %) (:publications repo))
                                         (:publications repo))
                                (::base ctx) (filter #(= (:lang (::base ctx)) (:lang %))))]
                     [(if (and (= "answer" action) target)
                        (or (seq (filter #(holds-change? % target) mine)) mine)
                        mine)
                      (if me? "one of your open publications" "publication")])
      :objection
      [(case action
         "unanswer" (filter #(some #{{:id (:id %)}} (:answers pub)) (:objections repo))
         "answer" (filter #(and (objection/draft? %)
                                (let [t (target-of repo :objection %)]
                                  (and t (= by (:owner t)) (or (nil? pub) (holds-change? pub t)))))
                          (:objections repo))
         (:objections repo))
       "objection"]
      :version [(if (and (= "react" action) target)
                  (filter #(objection/newer? target %) (filter :minor (lineage-of repo target)))
                  (published-versions repo))
                "published version"]
      :draft [(filter #(or (= by (:owner %))
                           (and (= "delete-draft" action) (= by (:owner (pub-of repo %)))))
                      (drafts repo))
              "draft"]
      :contributor
      (let [users (filter #(some #{(:id %)} (map :id (:users repo))) (:persons repo))]
        [(case action
           "invite-contributor"
           (remove #(some #{{:id (:id %)}} (cons (:owner pub) (:contributors pub))) users)
           "revoke-contributor" (filter #(some #{{:id (:id %)}} (:contributors pub)) users)
           users)
         "user"])
      [nil (name k)])))

(declare open-publication-for!)

(def ^:private new-publication ::new-publication)

(defn- reference
  "Picks the entity `k` designates, as `{:id}`: among its candidates, the single one taken
  without asking. Writing a draft, a new publication may be opened for it instead."
  [repo ctx k]
  (let [[xs prompt] (candidates repo ctx k)]
    (cond
      (and (= :publication k) (= "write-draft" (::action ctx)) (map? (::by ctx)) (empty? xs))
      (open-publication-for! repo ctx)
      (and (= :publication k) (= "write-draft" (::action ctx)) (map? (::by ctx)))
      (let [chosen (pick prompt
                         (concat xs [new-publication])
                         #(if (= new-publication %) "✚ a new publication for it" (label repo %)))]
        (cond
          (= new-publication chosen) (open-publication-for! repo ctx)
          chosen (select-keys chosen [:id])))
      :else (case (count xs)
              0 (do (println (b/bling [:italic.gray (str "  nothing to pick as " prompt)])) nil)
              1 (do (println (b/bling [:gray (str "  " prompt ": ")] (label repo (first xs))))
                    (select-keys (first xs) [:id]))
              (select-keys (pick prompt xs #(label repo %)) [:id])))))

(defn- citation
  "Picks a citation: a published version by tnlr, or a draft of the same publication by id.
  Backing an objection, only the types its reason admits."
  [repo ctx]
  (let [reason (some-> (::target ctx)
                       :type
                       (type/objection-of (::reason ctx)))
        admitted? (if reason #(contains? (:backed-by reason) (:type %)) (constantly true))
        pub (some->> ctx
                     ::fixed :publication
                     :id (pub-by-id repo))
        choices (concat (for [d (published-versions repo)
                              :when (admitted? d)]
                          [(str "tnlr  " (version-label d))
                           (select-keys d [:type :name :lang :major])])
                        (for [d (:documents pub)
                              :when (not (:minor d))]
                          [(str "draft " (version-label d)) {:id (:id d)}]))]
    (when (seq choices) (second (pick "cite" choices first)))))

(declare value-map)

(defn- enum-values [schema] (when (and (vector? schema) (= :enum (first schema))) (rest schema)))

(defn- choose
  "A keyword among `options`, `default` first."
  [prompt options default]
  (keyword (fzf prompt
                (distinct (map name
                               (cond->> options
                                 default (cons default)))))))

(defn- value
  "A value for `k` following `schema`, picked or typed; `default` proposed."
  [repo ctx k schema default]
  (let [schema (if (and (vector? schema) (#{:and} (first schema))) (second schema) schema)]
    (cond
      (= :id k) (or default (random-uuid))
      (str/ends-with? (name k) "-at") (java.util.Date.)
      (ref? schema) (reference repo ctx k)
      (= :inputs k) (if (and (seq default)
                             (not (yes? (str "change its " (count default) " citations?"))))
                      (vec default)
                      (loop [acc []]
                        (if (yes? (str "add a citation (" (count acc) " so far)"))
                          (recur (if-let [c (citation repo ctx)]
                                   (conj acc c)
                                   acc))
                          acc)))
      (= :reason k) (let [reasons (some->> (::target ctx)
                                           :type type/definition-of
                                           :objections (map :id))]
                      (if (seq reasons)
                        (choose "reason" reasons default)
                        (keyword (ask "reason"
                                      (some-> default
                                              name)))))
      (enum-values schema) (choose (name k) (enum-values schema) default)
      (and (vector? schema) (= := (first schema))) (second schema)
      (and (vector? schema) (= :map (first schema))) (value-map repo ctx schema default)
      (#{:int} schema) (parse-long (ask (name k)
                                        (some-> default
                                                str)))
      (and (vector? schema) (= :maybe (first schema)))
      (let [s (ask (str (name k) " (empty for none)") default)] (when-not (str/blank? s) s))
      :else (ask (name k) default))))

(defn- known
  "What a value just set teaches the next questions."
  [repo ctx k v]
  (cond-> ctx
    (and (= :objection k) v)
    (assoc ::target
           (if (= "react" (::action ctx))
             (latest-target repo (some #(when (= (:id v) (:id %)) %) (:objections repo)))
             (target-of repo :objection v)))
    (and (= :version k) v (not= "react" (::action ctx))) (assoc ::target
                                                                (target-of repo :version v))
    (= :reason k) (assoc ::reason v)
    (= :type k) (assoc ::type v)))

(def ^:private only-for
  "The optional keys that only some types have."
  {:author #{:work}
   :editor #{:work}
   :year #{:work}
   :url #{:work}
   :locator #{:extract}})

(defn- irrelevant?
  "Is the optional key `k` meaningless for the type being written?"
  [ctx k]
  (when-let [types (only-for k)] (and (::type ctx) (not (types (::type ctx))))))

(defn- free-name
  "`drawn`, or `drawn-2`, `drawn-3`… : the first no publication in `lang` has."
  [repo lang drawn]
  (let [taken (set (keep #(when (= lang (:lang %)) (:name %)) (:publications repo)))]
    (first (remove taken (cons drawn (map #(str drawn "-" %) (iterate inc 2)))))))

(defn- derived
  "A default the answers already given imply: a name drawn from the title, the next major
  of a name already taken."
  [repo ctx m k]
  (case k
    :name (if-let [t (get-in m [:content :title])]
            (name/from-title t)
            (when-let [t (:title m)] (free-name repo (:lang m) (name/from-title t))))
    :major (let [lang (or (some->> ctx
                                   ::fixed
                                   :publication
                                   :id
                                   (pub-by-id repo)
                                   :lang)
                          (:lang m))
                 majors (map :major (lineage-of repo (assoc m :lang lang)))]
             (if (seq majors) (inc (apply max majors)) 1))
    nil))

(defn- in-order
  "The children of a grammar, the name and the major last: they derive from the title."
  [grammar]
  (let [cs (children grammar)
        late? (comp #{:name :major} first)]
    (concat (remove late? cs) (filter late? cs))))

(defn value-map
  "A map following the `[:map …]` grammar, key by key: a key of `fixed` takes its value,
  the others are picked or typed, `default` proposed, or a value the answers imply. An
  optional key is asked only when nothing it starts from is known."
  [repo ctx grammar default & [fixed]]
  (first (reduce
          (fn [[m ctx] [k props schema]]
            (cond
              (and (map? (get fixed k)) (vector? schema) (= :map (first schema)))
              [(assoc m k (value-map repo ctx schema (get default k) (get fixed k))) ctx]
              (contains? fixed k) [(assoc m k (get fixed k)) (known repo ctx k (get fixed k))]
              (and (:optional props)
                   (not (contains? default k))
                   (or (map? default) (irrelevant? ctx k) (not (yes? (str "set " (name k) "?")))))
              [m ctx]
              :else (let [v (value repo ctx k schema (get default k (derived repo ctx m k)))]
                      [(cond-> m
                         (some? v) (assoc k v))
                       (known repo ctx k v)])))
          [{} ctx]
          (in-order grammar))))

(defn- defaults
  "What an action started from the menu proposes: the draft or objection it edits, the name
  of who acts."
  [repo action-sym by]
  (case (name action-sym)
    "edit-draft" (when-let [r (reference repo
                                         {::by by
                                          ::action "edit-draft"}
                                         :draft)]
                   {:draft (select-keys (some #(when (= (:id r) (:id %)) %) (drafts repo))
                                        [:id :author :content])})
    "edit-objection" (when-let [r (reference repo {::by by} :objection)]
                       {:objection r
                        :content (:content (some #(when (= (:id r) (:id %)) %)
                                                 (:objections repo)))})
    "rename-user" (when (map? by) {:name (person-name repo by)})
    nil))

(defn parameters
  "The parameters of `action-var`, asked one by one. `fixed` gives the values the screen
  already knows; `default` proposes the others, else what the action starts from."
  ([repo action-var] (parameters repo action-var nil nil nil nil))
  ([repo action-var fixed default by extra]
   (let [action (name (symbol action-var))
         start (merge (when-not fixed (defaults repo (symbol action-var) by)) default)
         ctx (reduce-kv #(known repo %1 %2 %3)
                        (merge extra
                               {::by by
                                ::action action
                                ::fixed fixed})
                        (merge (select-keys start [:objection]) fixed))]
     (walk/postwalk
      #(if (map? %)
         (apply dissoc % [::target ::reason ::type ::by ::action ::fixed ::base ::gesture])
         %)
      (value-map repo ctx (:parameters (meta action-var)) start fixed)))))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn actions
  "Every action of the domain: `{symbol var}`."
  []
  (into (sorted-map)
        (for [f (fs/list-dir (io/file (io/resource "agora/action")))
              :let [n (symbol (str "agora.action."
                                   (-> (str (fs/file-name f))
                                       (str/replace #"\.cljc$" "")
                                       (str/replace "_" "-"))))]
              :when (str/ends-with? (str f) ".cljc")
              :let [_ (require n)]
              [s v] (ns-publics n)
              :when (:parameters (meta v))]
          [(symbol (str n) (str s)) v])))

(defn- summary
  [v]
  (first (str/split-lines (or (:doc (meta (the-ns (symbol (namespace (symbol v)))))) ""))))

(defn- attempt
  "`[after changes]` of running `action-var` as `by` with `params`, or nil once its refusal
  is shown. Saves nothing."
  [repo by action-var params]
  (try (let [changes (action-var repo by params)] [(repository/apply-changes repo changes) changes])
       (catch clojure.lang.ExceptionInfo e
         (if-let [reason (:reason (ex-data e))]
           (do (println)
               (b/callout {:type :error
                           :label "Refused"}
                          (b/bling [:bold (str reason)]
                                   (when-let [why (refusal-explanation action-var reason)]
                                     (str "\n" why))
                                   (when-let [v (:violations (ex-data e))] (str "\n" (pr-str v)))))
               nil)
           (throw e)))))

(defn- done
  [action-var after changes]
  (println)
  (println (b/bling [:bold.green "✔ "] [:bold (name (symbol action-var))] [:green " done"]))
  (show-changes after changes))

(defn perform!
  "Runs `action-var` as `by` with `params`: saves the changes, or shows the refusal."
  [repo by action-var params]
  (if-let [[after changes] (attempt repo by action-var params)]
    (do (save-repo! after) (done action-var after changes) after)
    repo))

(defn login!
  "Picks who acts among the users, or nobody."
  [repo]
  (let [users (for [u (:users repo)] {:id (:id u)})
        by (or (pick "log in as"
                     (cons :anonymous users)
                     #(if (= :anonymous %) "nobody (log out)" (person-name repo %)))
               :anonymous)]
    (save-by! by)
    by))

(defn- use-case-file [f] (edn/read-string (slurp (io/file "agora/use_cases" f))))

(def ^:private agora-id (get-in user/agora [:user :id]))

(defn init!
  "A new repository: the vocabulary of types, which every document needs, and Agora, its
  owner; with `story?`, every person, user and snapshot of the use cases."
  [story?]
  (let [files (if story?
                (filter #(and (str/ends-with? % ".edn") (vector? (use-case-file %)))
                        (map (comp str fs/file-name) (fs/list-dir "agora/use_cases")))
                ["type-vocabulary.edn"])
        content (mapcat use-case-file files)
        keep? (if story? (constantly true) #(= agora-id (:id %)))
        repo (merge repository/empty-repository
                    {:persons (filterv keep? (:persons (use-case-file "persons.edn")))
                     :users (filterv keep? (:users (use-case-file "users.edn")))
                     :publications (filterv #(= :publication (:type %)) content)
                     :objections (filterv #(= :objection (:type %)) content)})]
    (save-repo! repo)
    (save-by! :anonymous)
    repo))

(defn- tagged [tag & parts] (str tag "\t" (apply b/bling parts)))

(defn- pub-item [repo p] (tagged (str "pub:" (:id p)) [:bold.blue "📚 "] (publication-line repo p)))

(defn- doc-item
  [repo d]
  (tagged (str "doc:" (:id d))
          [:bold.orange "📄 "]
          [:bold.orange (format "%-36s" (version-label d))]
          (if (:minor d) (badge :published) (badge :draft))
          [:gray (format "%-14s" (person-name repo (:owner d)))]
          (clip 40
                (-> d
                    :content
                    :title))))

(defn- obj-item
  [repo o & [prefix]]
  (tagged (str "obj:" (:id o)) [:bold.magenta (str (or prefix "⚑") " ")] (objection-line repo o)))

(defn- user-item
  [repo u]
  (tagged (str "user:" (:id u)) [:bold.cyan "👤 "] [:bold (person-name repo u)]))

(def ^:private shortcuts
  "Screen actions that are another action with values already set, and what they say."
  {"new-major" ["write-draft" "a new major of this document, in one of your open publications"]
   "new-minor" ["write-draft"
                "a new minor of this document, yours, in one of your open publications"]})

(defn- action-var
  [label]
  (let [n (first (shortcuts label [label]))] ((actions) (symbol (str "agora.action." n) n))))

(defn- action-item
  [label]
  (tagged (str "act:" label)
          [:bold.yellow "⚡ "]
          [:yellow (format "%-20s" label)]
          [:gray (or (second (shortcuts label)) (summary (action-var label)))]))

(def ^:private back-item (tagged "back" [:gray "← back"]))

(defn- screen
  "Picks one of `items`, the detail of the one hovered beside; its tag, or \"back\"."
  [title repo by items]
  (let [{:keys [out exit]}
        @(p/process
          ["fzf"
           "--ansi"
           "--height=95%"
           "--reverse"
           "--delimiter=\t"
           "--with-nth=2.."
           "--header"
           (str (b/bling [:bold.orange "Agora"] [:gray "  logged in as "] [:bold (who repo by)])
                "\n"
                title)
           "--prompt"
           "> "
           "--preview"
           (str "bb agora --repo " *repo-file* " preview {1}")
           "--preview-window=right:50%:wrap"]
          {:in (str/join "\n" items)
           :out :string
           :err :inherit})]
    (if (zero? exit) (first (str/split (str/trim out) #"\t")) "back")))

(defn- doc-actions
  [repo by d]
  (let [p (pub-of repo d)
        pref {:id (:id p)}
        lineage (filter #(= ((juxt :type :name :lang) d) ((juxt :type :name :lang) %))
                        (publication/documents (:publications repo)))
        draft-of (fn [major]
                   {:draft {:type (:type d)
                            :name (:name d)
                            :major major}})]
    (if (:minor d)
      (cond-> []
        (or (not (type/description? d)) (= by {:id agora-id}))
        (conj ["new-major"
               (draft-of (inc (apply max (map :major lineage))))
               {:draft (select-keys d [:author :content])}
               {::base d
                ::gesture :new-major}])
        (= by (:owner d)) (conj ["new-minor"
                                 (draft-of (:major d))
                                 {:draft (select-keys d [:author :content])}
                                 {::base d
                                  ::gesture :new-minor}])
        true (conj ["object" {:version {:id (:id d)}}])
        (type/timestamp? (:type d)) (conj ["prepare-timestamp" {:version {:id (:id d)}}])
        (:timestamp d) (conj ["record-timestamp" {:version {:id (:id d)}}]))
      [["edit-draft" {:publication pref} {:draft (select-keys d [:id :author :content])}]
       ["delete-draft"
        {:publication pref
         :draft {:id (:id d)}}]])))

(defn- pub-actions
  [p]
  (let [r {:publication {:id (:id p)}}]
    (if (publication/open? p)
      [["write-draft" r]
       ["edit-publication" r {:content (:content p)}]
       ["invite-contributor" r]
       ["revoke-contributor" r]
       ["answer" r]
       ["unanswer" r]
       ["publish" r]
       ["cancel-publication" r]]
      [["take-up" r {:content (:content p)}]])))

(defn- obj-actions
  [o]
  (let [r {:objection {:id (:id o)}}]
    (case (:status o)
      :draft [["edit-objection" r {:content (:content o)}] ["cancel-objection" r] ["answer" r]]
      :closed [["react" r {:content (:content o)}]]
      [])))

(def ^:private gesture-texts
  "The title and the text of a publication opened for a single gesture, by language."
  {:fr {:new-major ["Nouvelle majeure de « %s »" "Propose la majeure %d de « %s »."]
        :new-minor ["Correction de « %s »" "Corrige la majeure %d de « %s »."]}
   :en {:new-major ["New major of “%s”" "Proposes major %d of “%s”."]
        :new-minor ["Correction of “%s”" "Corrects major %d of “%s”."]}})

(defn- gesture-publication
  "The parameters of the publication opened for the gesture of `ctx` on its base document:
  everything derived, in the document's language."
  [repo ctx]
  (let [base (::base ctx)
        lang (:lang base)
        doc-title (-> base
                      :content
                      :title)
        major (or (get-in ctx [::fixed :draft :major]) (:major base))
        [title-format text-format] (get-in gesture-texts [lang (::gesture ctx)])
        title (format title-format doc-title)]
    {:id (random-uuid)
     :name (free-name repo lang (name/from-title title))
     :lang lang
     :title title
     :text (format text-format major doc-title)
     :published-at (java.util.Date.)}))

(def ^:dynamic *pending*
  "An atom holding the publication to open before the action run, as `[action-var params]`."
  nil)

(defn- open-publication-for!
  "The publication for the draft about to be written, as `{:id}`. For a gesture on a
  document, every field is derived from it, and it opens with the draft: both or neither.
  Otherwise its fields are asked and it opens at once; nil when refused."
  [repo ctx]
  (let [v ((actions) 'agora.action.open-publication/open-publication)]
    (if (and (::base ctx) (::gesture ctx) *pending*)
      (let [params (gesture-publication repo ctx)]
        (println (b/bling [:gray "  in a new publication: "] [:bold (:title params)]))
        (reset! *pending* [v params])
        {:id (:id params)})
      (let [before (set (map :id (:publications repo)))
            _ (println (b/bling [:bold.yellow "  the new publication"]
                                [:gray "  then the draft it holds"]))
            params (binding [*subject* "publication"] (parameters repo v nil nil (::by ctx) nil))
            after (perform! repo (::by ctx) v params)]
        (some #(when-not (before (:id %)) {:id (:id %)}) (:publications after))))))

(defn- subject-of
  "What the questions of `v` are about: the kind it writes."
  [v]
  (let [n (name (symbol v))]
    (some (fn [[re subject]] (when (re-find re n) subject))
          [[#"draft$" "draft"]
           [#"publication$" "publication"]
           [#"objection$|^object$|^react$" "objection"]])))

(defn- do-run
  "Runs `v`, labelled `label`, with the values the screen knows."
  [repo by v label fixed default extra]
  (println)
  (println (b/bling [:bold.yellow (str "⚡ " label)]
                    [:gray (str "  " (or (second (shortcuts label)) (summary v)))]))
  (binding [*pending* (atom nil)]
    (let [params (binding [*subject* (subject-of v)] (parameters repo v fixed default by extra))]
      (if-let [[pv pparams] @*pending*]
        (or (when-let [[opened pchanges] (attempt repo by pv pparams)]
              (when-let [[after changes] (attempt opened by v params)]
                (save-repo! after)
                (done pv opened pchanges)
                (done v after changes)
                after))
            repo)
        ;; asking the parameters may have opened a publication: act on the saved repository
        (perform! (load-repo) by v params)))))

(defn- run-from-screen
  "Runs the action labelled `label` with the values the screen knows."
  [repo by [label fixed default extra]]
  (let [v (action-var label)]
    (if (and (= "erase-account" label)
             (not (yes? "erase your account for good? its writings go to Anonymus")))
      repo
      (do-run repo by v label fixed default extra))))

(defn navigate
  "The interactive navigation: from the home screen down to publications, documents,
  objections and users, each with the actions that apply to it."
  []
  (loop [repo (load-repo)
         by (load-by)
         stack [[:home]]]
    (let [[kind id] (peek stack)
          up (if (> (count stack) 1) (pop stack) stack)
          acts (case kind
                 :pub (pub-actions (pub-by-id repo id))
                 :doc (doc-actions repo by (doc-by-id repo id))
                 :obj (obj-actions (obj-by-id repo id))
                 :user (when (= by {:id id}) [["rename-user" {}] ["erase-account" {}]])
                 :pubs [["open-publication" {}]]
                 :home [["sign-up" {}] ["create-person" {}]]
                 [])
          by-name (into {} (map (juxt first identity)) acts)
          action-items (map (comp action-item first) acts)
          [title items]
          (case kind
            :home [(b/bling [:bold "Home"])
                   (concat [(tagged "go:pubs"
                                    [:bold.blue "📚 Publications"]
                                    [:gray (str "  " (count (:publications repo)))])
                            (tagged "go:objs"
                                    [:bold.magenta "⚑ Objections"]
                                    [:gray (str "  " (count (:objections repo)))])
                            (tagged "go:users"
                                    [:bold.cyan "👤 Users"]
                                    [:gray (str "  " (count (:users repo)))])]
                           action-items
                           [(tagged "login" [:cyan "↪ log in as…"])
                            (tagged "new" [:gray "✚ new repository"])
                            (tagged "new-story" [:gray "✚ new repository, from the use cases"])
                            (tagged "quit" [:gray "✕ quit"])])]
            :pubs [(b/bling [:bold.blue "📚 Publications"])
                   (concat (map #(pub-item repo %) (:publications repo)) action-items [back-item])]
            :objs [(b/bling [:bold.magenta "⚑ Objections"])
                   (concat (map #(obj-item repo %) (:objections repo)) [back-item])]
            :users [(b/bling [:bold.cyan "👤 Users"])
                    (concat (map #(user-item repo %) (:users repo)) [back-item])]
            :pub (let [p (pub-by-id repo id)]
                   [(b/bling [:bold.blue "📚 "] (publication-line repo p))
                    (concat (map #(doc-item repo %) (:documents p))
                            (keep (fn [a]
                                    (when-let [o (obj-by-id repo (:id a))]
                                      (obj-item repo o "↩ answers")))
                                  (:answers p))
                            action-items
                            [back-item])])
            :doc (let [d (doc-by-id repo id)]
                   [(b/bling [:bold.orange "📄 " (version-label d)]
                             "  "
                             (-> d
                                 :content
                                 :title))
                    (concat [(pub-item repo (pub-of repo d))]
                            (map #(obj-item repo %)
                                 (filter #(= [{:id id}] (:documents %)) (:objections repo)))
                            action-items
                            [back-item])])
            :obj (let [o (obj-by-id repo id)
                       target (doc-by-id repo (:id (first (:documents o))))]
                   [(b/bling [:bold.magenta "⚑ "] (objection-line repo o))
                    (concat (when target [(doc-item repo target)])
                            (map #(pub-item repo %)
                                 (filter #(some #{{:id id}} (:answers %)) (:publications repo)))
                            (map #(obj-item repo % "⚑ other major")
                                 (filter #(and (= ((juxt :name :lang) o) ((juxt :name :lang) %))
                                               (not= id (:id %)))
                                         (:objections repo)))
                            action-items
                            [back-item])])
            :user (let [mine #(= {:id id} (:owner %))]
                    [(b/bling [:bold.cyan "👤 " (person-name repo {:id id})])
                     (concat [(tagged (str "login:" id)
                                      [:cyan (str "↪ log in as " (person-name repo {:id id}))])]
                             (map #(pub-item repo %) (filter mine (:publications repo)))
                             (map #(obj-item repo %) (filter mine (:objections repo)))
                             action-items
                             [back-item])]))
          tag (screen title repo by items)
          [t v] (str/split tag #":" 2)
          uuid (some-> v
                       parse-uuid)]
      (case t
        "quit" nil
        "back" (if (= kind :home) nil (recur repo by up))
        "go" (recur repo by (conj stack [(keyword v)]))
        "pub" (recur repo by (conj stack [:pub uuid]))
        "doc" (recur repo by (conj stack [:doc uuid]))
        "obj" (recur repo by (conj stack [:obj uuid]))
        "user" (recur repo by (conj stack [:user uuid]))
        "login" (recur repo (if uuid (do (save-by! {:id uuid}) {:id uuid}) (login! repo)) stack)
        "new" (recur (init! false) :anonymous [[:home]])
        "new-story" (recur (init! true) :anonymous [[:home]])
        "act" (let [after (run-from-screen repo by (by-name v))
                    by (if (and (map? by) (not (some #(= (:id by) (:id %)) (:users after))))
                         (do (save-by! :anonymous) :anonymous)
                         by)
                    still? (case kind
                             :pub (pub-by-id after id)
                             :doc (doc-by-id after id)
                             :obj (obj-by-id after id)
                             :user (some #(= id (:id %)) (:persons after))
                             true)]
                (print (b/bling [:gray "  press Enter to go on "]))
                (flush)
                (read-line)
                (recur after by (if still? stack [[:home]])))
        (recur repo by stack)))))

(defn preview
  "What the navigation shows beside the item `tag`: its detail, or the doc of an action."
  [repo tag]
  (let [[t v] (str/split (str tag) #":" 2)]
    (case t
      ("pub" "doc" "obj" "user") (detail repo v)
      "act" (when-let [a (action-var v)]
              (println (b/bling [:bold.yellow v]))
              (when-let [[n why] (shortcuts v)]
                (println (b/bling [:gray (str why " — the action " n ":")]))
                (println))
              (println (:doc (meta (the-ns (symbol (namespace (symbol a)))))))
              (println)
              (println (:doc (meta a))))
      "go" (case v
             "pubs" (doseq [p (:publications repo)] (println (publication-line repo p)))
             "objs" (doseq [o (:objections repo)] (println (objection-line repo o)))
             "users" (doseq [u (:users repo)] (println (person-name repo u)))
             nil)
      nil)))

(def help
  "bb agora                      navigate: publications, documents, objections, users, each
                              with the actions that apply to it
bb agora show                 a summary of the repository
bb agora detail NAME          the detail of a publication, objection or user (name or id)
bb agora actions              list the actions
bb agora login                pick who acts
bb agora run ACTION PARAMS    run an action, PARAMS an EDN map, e.g.
                              bb agora run sign-up '{:id #uuid \"…\" :name \"Ana\" :provider :password :provider-id \"ana\"}'
bb agora init [--story]       a new repository: the vocabulary of types, or all the use cases
Options: --repo FILE          the repository file (default cli-data/agora.edn)
         --as NAME            with run: act as the user of that name")

(defn -main
  [& args]
  (let [[opts args] (loop [opts {}
                           [a & more :as all] args
                           rest []]
                      (cond
                        (nil? all) [opts rest]
                        (= "--repo" a) (recur (assoc opts :repo (first more)) (next more) rest)
                        (= "--as" a) (recur (assoc opts :as (first more)) (next more) rest)
                        :else (recur opts more (conj rest a))))]
    (binding [*repo-file* (or (:repo opts) *repo-file*)]
      (case (first args)
        nil (navigate)
        "show" (show (load-repo))
        "browse" (navigate)
        "preview" (preview (load-repo) (second args))
        "detail" (detail (load-repo) (second args))
        "actions" (doseq [[s v] (actions)]
                    (println (b/bling [:bold (name s)] [:gray (str "  " (summary v))])))
        "login" (login! (load-repo))
        "init" (do (init! (= "--story" (second args)))
                   (println (b/bling [:green "✔ new repository "] *repo-file*)))
        "run" (let [all (actions)
                    v (some (fn [[s v]] (when (= (second args) (name s)) v)) all)]
                (if v
                  (let [repo (load-repo)
                        by (if-let [n (:as opts)]
                             (or (some #(when (= n (:name %)) {:id (:id %)}) (:persons repo))
                                 {:id (random-uuid)})
                             (load-by))]
                    (perform! repo by v (edn/read-string (nth args 2 "{}"))))
                  (println (b/bling [:red "unknown action "] (str (second args))))))
        (println help)))))
