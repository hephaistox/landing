(ns agora.wiki
  "Writes the wiki pages describing the agora domain, in English and in French.

The English is read from the code: the docstrings of the concepts, the actions and their
parameters, the rules. It never drifts from the domain. The French comes from
`agora/wiki/fr.edn`: each translation keeps the hash of the English it translates, so a
translation whose English changed is reported as outdated, never published as is.

Pages, named for the GitHub wiki, which ignores folders: `Agora-<page>-<lang>`."
  (:require
   [agora.value.hash :as hash]
   [clojure.edn      :as edn]
   [clojure.java.io  :as io]
   [clojure.string   :as str]))

(def concepts
  "The namespaces defining a concept, in reading order."
  '[agora.entity.person
    agora.entity.user
    agora.entity.document
    agora.entity.publication
    agora.entity.objection
    agora.value.type
    agora.value.name
    agora.value.lang
    agora.value.version
    agora.value.identity
    agora.value.reference
    agora.value.citation
    agora.value.hash
    agora.value.timestamp
    agora.repository
    agora.action])

(def rule-vars
  "The vars holding rules, by concept."
  '[agora.repository/rules
    agora.entity.person/rules
    agora.entity.user/rules
    agora.entity.document/rules
    agora.entity.publication/rules
    agora.entity.publication/person-rules
    agora.entity.objection/rules
    agora.value.lang/rules
    agora.value.type/rules
    agora.value.version/rules
    agora.value.identity/rules
    agora.value.citation/rules
    agora.value.timestamp/rules])

(def translations-file "agora/wiki/fr.edn")

(defn- fingerprint [s] (subs (hash/sha-256 s) 0 12))

(defn- concept-name [ns-sym] (str/trim (first (str/split-lines (:doc (meta (find-ns ns-sym)))))))

(defn- concept-doc
  "The docstring of `ns-sym` without its title line, its headings one level down."
  [ns-sym]
  (->> (rest (str/split-lines (:doc (meta (find-ns ns-sym)))))
       (map #(if (str/starts-with? % "#") (str "#" %) %))
       (str/join "\n")
       str/trim))

(defn- action-namespaces
  []
  (sort-by str (filter #(str/starts-with? (str (ns-name %)) "agora.action.") (all-ns))))

(defn actions
  "Every action: `{:sym :ns-doc :doc :parameters}`."
  []
  (for [n (action-namespaces)
        [_ v] (sort-by key (ns-publics n))
        :when (:parameters (meta v))]
    {:sym (symbol v)
     :ns-doc (-> (:doc (meta n))
                 (str/replace #"(?s)\n*An action takes the repository.*$" "")
                 str/trim)
     :doc (:doc (meta v))
     :parameters (:parameters (meta v))}))

(defn- parameter-keys
  "The keys of a `[:map …]` grammar, `:k?` when optional."
  [grammar]
  (when (and (vector? grammar) (= :map (first grammar)))
    (for [child (rest grammar)
          :when (vector? child)
          :let [[k props] child]]
      (str "`" k (when (and (map? props) (:optional props)) "?") "`"))))

(defn rules
  "Every rule: `{:id :description :concept}`."
  []
  (for [v rule-vars
        r @(requiring-resolve v)]
    (assoc (select-keys r [:id :description]) :concept (namespace v))))

(defn- action-text [{:keys [ns-doc doc]}] (str ns-doc "\n\n" doc))

(defn english
  "Every text to translate, by `[kind key]`."
  []
  (merge (into {} (for [c concepts] [[:concepts c] (concept-doc c)]))
         (into {} (for [a (actions)] [[:actions (:sym a)] (action-text a)]))
         (into {} (for [r (rules)] [[:rules (:id r)] (:description r)]))))

(defn- read-translations
  []
  (let [f (io/file translations-file)] (if (.exists f) (edn/read-string (slurp f)) {})))

(defn translation-status
  "`{:missing [...] :outdated [...]}`: the keys without French, or whose English changed."
  []
  (let [fr (read-translations)
        en (english)]
    {:missing (vec (for [[k _] en :when (nil? (get-in fr k))] k))
     :outdated (vec (for [[k text] en
                          :let [t (get-in fr k)]
                          :when (and t (not= (:en-hash t) (fingerprint text)))]
                      k))}))

(defn- french
  "The French of `k`, when it translates the current English; else nil."
  [fr k english-text]
  (let [t (get-in fr k)] (when (= (:en-hash t) (fingerprint english-text)) (:fr t))))

(def chrome
  {:en
   {:lang "English"
    :other "[Version française](Agora-%s-fr)"
    :domain "Agora — the domain"
    :concepts "Agora — concepts"
    :actions "Agora — actions"
    :rules "Agora — rules"
    :parameters "Parameters"
    :rule "rule"
    :concept "concept"
    :description "what is checked"
    :untranslated ""
    :intro
    "Agora lets people write and read **living articles**: every claim cites what it rests on, can be contested by an objection, and evolves through versions, published together in publications.

The domain is pure: plain data and functions, the same in Clojure and ClojureScript. A change is only made by an **action**, a function of the repository, of who acts and of its parameters, which returns its changes or refuses with a reason. Every concept holds its **rules**, checked on the repository each action gives.

- [Concepts](Agora-concepts-en): what each concept means, its goals and examples.
- [Actions](Agora-actions-en): everything someone does in agora, and why it may be refused.
- [Rules](Agora-rules-en): what always holds."}
   :fr
   {:lang "Français"
    :other "[English version](Agora-%s-en)"
    :domain "Agora — le domaine"
    :concepts "Agora — concepts"
    :actions "Agora — actions"
    :rules "Agora — règles"
    :parameters "Paramètres"
    :rule "règle"
    :concept "concept"
    :description "ce qui est vérifié"
    :untranslated "*Traduction à venir ; le texte anglais suit.*\n\n"
    :intro
    "Agora permet d'écrire et de lire des **articles vivants** : chaque affirmation cite ce sur quoi elle repose, peut être contestée par une objection, et évolue par versions, publiées ensemble dans des publications.

Le domaine est pur : des données et des fonctions, identiques en Clojure et en ClojureScript. Un changement ne se fait que par une **action**, fonction du dépôt, de qui agit et de ses paramètres, qui rend ses changements ou refuse avec une raison. Chaque concept porte ses **règles**, vérifiées sur le dépôt que donne chaque action.

- [Concepts](Agora-concepts-fr) : ce que veut dire chaque concept, ses buts et ses exemples.
- [Actions](Agora-actions-fr) : tout ce que l'on fait dans agora, et pourquoi c'est refusé.
- [Règles](Agora-rules-fr) : ce qui est toujours vrai."}})

(defn- text
  "The text of `k` in `lang`: the French when translated, else a notice then the English."
  [lang fr k english-text]
  (if (= :en lang)
    english-text
    (or (french fr k english-text) (str (get-in chrome [:fr :untranslated]) english-text))))

(defn- header
  [lang page title]
  (str "# " title "\n\n" (format (get-in chrome [lang :other]) page) "\n\n"))

(defn- domain-page
  [lang]
  (str (header lang "domain" (get-in chrome [lang :domain])) (get-in chrome [lang :intro]) "\n"))

(defn- concepts-page
  [lang fr]
  (str (header lang "concepts" (get-in chrome [lang :concepts]))
       (str/join "\n\n"
                 (for [c concepts]
                   (str "## " (concept-name c)
                        "\n\n`" c
                        "`\n\n" (text lang fr [:concepts c] (concept-doc c)))))
       "\n"))

(defn- actions-page
  [lang fr]
  (str (header lang "actions" (get-in chrome [lang :actions]))
       (str/join
        "\n\n"
        (for [a (actions)]
          (str "## " (name (:sym a))
               "\n\n`" (:sym a)
               "`\n\n" (str/replace (text lang fr [:actions (:sym a)] (action-text a)) #"\n  " "\n")
               "\n\n**" (get-in chrome [lang :parameters])
               "** : " (str/join ", " (parameter-keys (:parameters a))))))
       "\n"))

(defn- rules-page
  [lang fr]
  (str (header lang "rules" (get-in chrome [lang :rules]))
       "| "
       (get-in chrome [lang :rule])
       " | "
       (get-in chrome [lang :concept])
       " | "
       (get-in chrome [lang :description])
       " |\n|---|---|---|\n"
       (str/join "\n"
                 (for [r (rules)]
                   (str "| `"
                        (:id r)
                        "` | `"
                        (:concept r)
                        "` | "
                        (str/replace (text lang fr [:rules (:id r)] (:description r)) "|" "\\|")
                        " |")))
       "\n"))

(defn generate!
  "Writes the pages in `dir`, and returns the translation status."
  [dir]
  (let [fr (read-translations)]
    (.mkdirs (io/file dir))
    (doseq [lang [:en :fr]
            [page f] {"domain" (fn [] (domain-page lang))
                      "concepts" (fn [] (concepts-page lang fr))
                      "actions" (fn [] (actions-page lang fr))
                      "rules" (fn [] (rules-page lang fr))}]
      (spit (io/file dir (str "Agora-" page "-" (name lang) ".md")) (f)))
    (translation-status)))

(defn write-english!
  "Writes every English text to translate, with its hash, to `file`: the source of a translation."
  [file]
  (spit file
        (with-out-str (doseq [[k t] (sort-by (comp str key) (english))]
                        (println "=====" (pr-str k) (fingerprint t))
                        (println t)))))
