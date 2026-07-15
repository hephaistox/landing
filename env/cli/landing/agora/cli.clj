(ns landing.agora.cli
  "An EDN-file CLI over the pure `landing.agora.corpus` domain — Agora with a flat EDN file as its
  entire store, no database. It loads a corpus, runs one command, prints a compact view, and (for
  a mutation) writes the corpus back. Its whole point is that the domain needs no infrastructure:
  every command is a `corpus/…` call over a value read from and written to disk, so the model can
  be exercised and reasoned about before any storage exists.

  Usage:  clojure -M:agora-cli <file.edn> <command> [args…]

    list                                          every lineage, compact (publications expanded)
    show <type> <name> <lang>                     one document (its current version), compact
    pub  <name> <lang>                            a publication with its members
    create <type> <name> <lang> <kind> <title…>   open a new draft document (kind omitted for a
                                                  publication: create publication <name> <lang> <title…>)
    edit   <type> <name> <lang> <title…>          retitle → a new minor

  Mutations rewrite <file.edn> in place."
  (:require
   [clojure.edn                    :as edn]
   [clojure.pprint                 :as pp]
   [clojure.string                 :as str]
   [landing.agora.corpus           :as corpus]
   [landing.agora.corpus-print     :as cprint]
   [landing.agora.document-lineage :as lineage]))

(defn- load-corpus [file] (edn/read-string (slurp file)))

(defn- save-corpus! [file corpus] (spit file (with-out-str (pp/pprint corpus))))

(defn- tnlr
  [type name lang]
  {:type type
   :name name
   :lang lang
   :major 1})

(defn- run
  [file cmd args]
  (let [c (load-corpus file)]
    (case cmd
      "list" (println (cprint/compact-corpus c))
      "show" (let [[type name lang] args]
               (if-let [d (lineage/latest (corpus/versions c (tnlr type name lang)))]
                 (println (cprint/compact-doc d))
                 (println "not found")))
      "pub" (let [[name lang] args]
              (if-let [p (lineage/latest (corpus/versions c (tnlr "publication" name lang)))]
                (println (cprint/compact-publication c p))
                (println "not found")))
      "create" (let [[type name lang & more] args
                     doc (if (= type "publication")
                           {:type type
                            :name name
                            :lang lang
                            :status "open"
                            :title (str/join " " more)
                            :author "cli"}
                           (let [[kind & title] more]
                             {:type type
                              :name name
                              :lang lang
                              :kind kind
                              :text ""
                              :title (str/join " " title)
                              :author "cli"}))
                     [c' d] (corpus/create c doc)]
                 (save-corpus! file c')
                 (println "created:" (cprint/compact-doc d)))
      "edit" (let [[type name lang & title] args
                   [c' d] (corpus/edit c (tnlr type name lang) {:title (str/join " " title)})]
               (if d
                 (do (save-corpus! file c') (println "edited: " (cprint/compact-doc d)))
                 (println "not found")))
      (println "unknown command:" cmd))))

(defn -main
  [& argv]
  (let [[file cmd & args] argv]
    (if (and file cmd)
      (run file cmd args)
      (println
       "usage: clojure -M:agora-cli <file.edn> <command> [args…]"
       "\n  commands: list | show <type> <name> <lang> | pub <name> <lang>"
       "| create <type> <name> <lang> <kind> <title…> | edit <type> <name> <lang> <title…>"))))
