(ns agora.cli
  "Agora from the command line: the actions of the domain, on a repository kept in an EDN
  file.

  `bb agora` opens a menu: who acts, which action, then each parameter, picked from lists
  (fzf) or typed. Ids and dates are made here, as a backend would. The action runs on the
  repository, its changes are applied and saved, or its refusal is shown with its reason.

  `bb agora help` lists the commands."
  (:require
   [agora.entity.publication :as publication]
   [agora.repository         :as repository]
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

(defn ask
  "A line typed after `prompt`; `default` when the line is empty."
  [prompt & [default]]
  (print
   (b/bling [:bold.blue (str "  " prompt)] (when default [:gray (str " [" default "]")]) ": "))
  (flush)
  (let [line (str/trim (or (read-line) ""))] (if (and (str/blank? line) default) default line)))

(defn yes? [prompt] (= "yes" (fzf prompt ["no" "yes"])))

;; ---------------------------------------------------------------------------
;; Parameters, from the grammar of an action
;; ---------------------------------------------------------------------------

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

(defn- reference
  "Picks the entity `k` designates, as `{:id}`."
  [repo k]
  (let [[xs prompt] (case k
                      :publication [(:publications repo) "publication"]
                      :objection [(:objections repo) "objection"]
                      :version [(published-versions repo) "published version"]
                      :draft [(drafts repo) "draft"]
                      :contributor
                      [(filter #(some #{(:id %)} (map :id (:users repo))) (:persons repo)) "user"]
                      [nil (name k)])]
    (if (seq xs)
      (select-keys (pick prompt xs #(label repo %)) [:id])
      (do (println (b/bling [:italic.gray (str "  nothing to pick as " prompt)])) nil))))

(defn- citation
  "Picks a citation: a published version by tnlr, or a draft by id."
  [repo]
  (let [choices (concat (for [d (published-versions repo)]
                          [(str "tnlr  " (version-label d))
                           (select-keys d [:type :name :lang :major])])
                        (for [d (drafts repo)] [(str "draft " (version-label d)) {:id (:id d)}]))]
    (when (seq choices) (second (pick "cite" choices first)))))

(declare value value-map)

(defn- enum-values [schema] (when (and (vector? schema) (= :enum (first schema))) (rest schema)))

(defn- value
  "A value for `k` following `schema`, picked or typed; `default` proposed."
  [repo ctx k schema default]
  (let [schema (if (and (vector? schema) (#{:and} (first schema))) (second schema) schema)]
    (cond
      (= :id k) (or default (random-uuid))
      (str/ends-with? (name k) "-at") (java.util.Date.)
      (ref? schema) (reference repo k)
      (= :inputs k) (loop [acc (vec default)]
                      (if (yes? (str "add a citation (" (count acc) " so far)"))
                        (recur (if-let [c (citation repo)]
                                 (conj acc c)
                                 acc))
                        acc))
      (= :reason k) (let [reasons (some->> (::target-type ctx)
                                           type/definition-of
                                           :objections
                                           (map :id))]
                      (if (seq reasons)
                        (keyword (fzf "reason" (map name reasons)))
                        (keyword (ask "reason"
                                      (some-> default
                                              name)))))
      (enum-values schema) (keyword (fzf (name k) (map name (enum-values schema))))
      (and (vector? schema) (= := (first schema))) (second schema)
      (and (vector? schema) (= :map (first schema))) (value-map repo ctx schema default)
      (#{:int} schema) (parse-long (ask (name k)
                                        (some-> default
                                                str)))
      (and (vector? schema) (= :maybe (first schema)))
      (let [s (ask (str (name k) " (empty for none)") default)] (when-not (str/blank? s) s))
      :else (ask (name k) default))))

(defn- target-of
  "The version a picked `:version` or `:objection` designates, to offer its type's reasons."
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

(defn value-map
  "A map following the `[:map …]` grammar, key by key: a key of `fixed` takes its value,
  the others are picked or typed, `default` proposed. An optional key is set on demand."
  [repo ctx grammar default & [fixed]]
  (reduce
   (fn [m [k props schema]]
     (cond
       (and (map? (get fixed k)) (vector? schema) (= :map (first schema)))
       (assoc m k (value-map repo (merge ctx m) schema (get default k) (get fixed k)))
       (contains? fixed k) (let [v (get fixed k)
                                 m (assoc m k v)]
                             (if-let [target (target-of repo k v)]
                               (assoc m ::target-type (:type target))
                               m))
       (and (:optional props) (not (contains? default k)) (not (yes? (str "set " (name k) "?")))) m
       :else (let [v (value repo (merge ctx m) k schema (get default k))
                   m (cond-> m
                       (some? v) (assoc k v))]
               (if-let [target (target-of repo k v)]
                 (assoc m ::target-type (:type target))
                 m))))
   {}
   (children grammar)))

(defn- defaults
  "What an edit starts from: the draft or objection it edits."
  [repo action-sym]
  (case (name action-sym)
    "edit-draft" (when-let [r (reference repo :draft)]
                   {:draft (select-keys (some #(when (= (:id r) (:id %)) %) (drafts repo))
                                        [:id :content])})
    "edit-objection" (when-let [r (reference repo :objection)]
                       {:objection r
                        :content (:content (some #(when (= (:id r) (:id %)) %)
                                                 (:objections repo)))})
    nil))

(defn parameters
  "The parameters of `action-var`, asked one by one. `fixed` gives the values the screen
  already knows; `default` proposes the others, else an edit starts from what it edits."
  ([repo action-var] (parameters repo action-var nil nil))
  ([repo action-var fixed default]
   (let [grammar (:parameters (meta action-var))
         start (or default (when-not fixed (defaults repo (symbol action-var))))
         o (or (:objection fixed) (:objection start))
         ctx (when o {::target-type (:type (target-of repo :objection o))})]
     (walk/postwalk #(if (map? %) (dissoc % ::target-type) %)
                    (value-map repo ctx grammar start fixed)))))

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

(defn perform!
  "Runs `action-var` as `by` with `params`: saves the changes, or shows the refusal."
  [repo by action-var params]
  (try (let [changes (action-var repo by params)
             after (repository/apply-changes repo changes)]
         (save-repo! after)
         (println)
         (println (b/bling [:bold.green "✔ "] [:bold (name (symbol action-var))] [:green " done"]))
         (show-changes after changes)
         after)
       (catch clojure.lang.ExceptionInfo e
         (if-let [reason (:reason (ex-data e))]
           (do (println)
               (b/callout {:type :error
                           :label "Refused"}
                          (b/bling [:bold (str reason)]
                                   (when-let [why (refusal-explanation action-var reason)]
                                     (str "\n" why))
                                   (when-let [v (:violations (ex-data e))] (str "\n" (pr-str v)))))
               repo)
           (throw e)))))

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

(def ^:private agora-id #uuid "00000000-0000-0000-0000-000000000001")

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

(defn- doc-by-id
  [repo id]
  (some #(when (= id (:id %)) %) (publication/documents (:publications repo))))

(defn- pub-of [repo d] (publication/from-publication (:publications repo) d))

(defn- obj-by-id [repo id] (some #(when (= id (:id %)) %) (:objections repo)))

(defn- pub-by-id [repo id] (some #(when (= id (:id %)) %) (:publications repo)))

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
      (cond-> [["new-major"
                (draft-of (inc (apply max (map :major lineage))))
                {:draft {:content (:content d)}}]]
        (= by (:owner d)) (conj
                           ["new-minor" (draft-of (:major d)) {:draft {:content (:content d)}}])
        true (conj ["object" {:version {:id (:id d)}}])
        (type/timestamp? (:type d)) (conj ["prepare-timestamp" {:version {:id (:id d)}}])
        (:timestamp d) (conj ["record-timestamp" {:version {:id (:id d)}}]))
      [["edit-draft" {:publication pref} {:draft (select-keys d [:id :content])}]
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
      :closed [["react" r]]
      [])))

(defn- run-from-screen
  "Runs the action labelled `label` with the values the screen knows."
  [repo by [label fixed default]]
  (let [v (action-var label)]
    (println)
    (println (b/bling [:bold.yellow (str "⚡ " label)]
                      [:gray (str "  " (or (second (shortcuts label)) (summary v)))]))
    (perform! repo by v (parameters repo v fixed default))))

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
