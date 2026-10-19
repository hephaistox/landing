(ns agora.use-case
  "Reads the agora use cases, one EDN file each, in `agora/use_cases`.
  A snapshot holds `:publications`; a scenario holds `:steps`, each saving publications."
  (:require
   [clojure.edn     :as edn]
   [clojure.java.io :as io]
   [clojure.string  :as str]))

(def dir "agora/use_cases")

(defn use-cases
  "Every use case: `{:file :publications}` or `{:file :steps}`, sorted by file."
  []
  (for [f (sort-by str (.listFiles (io/file dir)))
        :when (str/ends-with? (.getName f) ".edn")]
    (assoc (edn/read-string (slurp f)) :file (.getName f))))

(defn snapshots [] (filter :publications (use-cases)))

(defn scenarios [] (filter :steps (use-cases)))

(defn initial-publications
  "The publications of every snapshot, the state scenarios start from."
  []
  (vec (mapcat :publications (snapshots))))

(defn- read-file [file] (edn/read-string (slurp (io/file dir file))))

(defn persons "Persons without an account." [] (:persons (read-file "personnes.edn")))

(defn users "Users: persons with an account." [] (:users (read-file "utilisateurs.edn")))
