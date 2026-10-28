(ns agora.use-case
  "Reads the agora use cases, one EDN file each, in `agora/use_cases`.
  A snapshot is a chronological vector of publications and objections. The action use
  cases, in `actions/`, start from snapshots (`agora.action-case`)."
  (:require
   [clojure.edn     :as edn]
   [clojure.java.io :as io]
   [clojure.string  :as str]))

(def dir "agora/use_cases")

(defn- read-file [file] (edn/read-string (slurp (io/file dir file))))

(defn- files
  "EDN files of the directory `d`, sorted."
  [d]
  (sort (for [f (.listFiles (io/file d)) :when (str/ends-with? (.getName f) ".edn")] (.getName f))))

(defn publications "The publications of `xs`." [xs] (filterv #(= :publication (:type %)) xs))

(defn objections "The objections of `xs`." [xs] (filterv #(= :objection (:type %)) xs))

(defn snapshots
  "Every snapshot: `{:file :content}`, its content a vector."
  []
  (for [f (files dir)
        :let [content (read-file f)]
        :when (vector? content)]
    {:file f
     :content content}))

(defn snapshot
  "The content of the snapshot `file`, or nil when there is none."
  [file]
  (let [f (io/file dir file)]
    (when (.isFile f) (let [content (read-file file)] (when (vector? content) content)))))

(defn initial-publications
  "The publications of every snapshot."
  []
  (publications (mapcat :content (snapshots))))

(defn initial-objections
  "The objections of every snapshot."
  []
  (objections (mapcat :content (snapshots))))

(defn persons "Every person the use cases name." [] (:persons (read-file "persons.edn")))

(defn users "Users: persons with an account." [] (:users (read-file "users.edn")))
