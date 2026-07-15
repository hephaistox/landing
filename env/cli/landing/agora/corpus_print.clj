(ns landing.agora.corpus-print
  "Compact, human-readable rendering of documents / publications / a whole corpus — for the CLI
  and the corpus tests. Pure presentation over the domain (`document-lineage`), kept out of both
  the `corpus` substrate and the shipped app."
  (:require
   [clojure.string                 :as str]
   [landing.agora.document-lineage :as lineage]))

(defn- ver [doc] (str (:major doc) "." (:minor doc)))

(defn compact-doc
  "One line describing a document:
  `<type> <name>@<major>.<minor> <lang> [<kind>] <status?> \"<title>\" ⇐ <inputs> ∈ <pub> (draft)`
  — the kind in brackets, a publication's `<status>` in angles, its inputs after `⇐`, publication
  membership after `∈`, and a `(draft)` flag. Absent parts are omitted."
  [doc]
  (let [ins (lineage/declared-inputs doc)]
    (str (:type doc)
         " "
         (:name doc)
         "@"
         (ver doc)
         " "
         (:lang doc)
         (when (:kind doc) (str " [" (:kind doc) "]"))
         (when (:status doc) (str " <" (:status doc) ">"))
         " \""
         (:title doc)
         "\""
         (when (seq ins) (str " ⇐ " (str/join ", " (map #(str (:name %) "@" (:major %)) ins))))
         (when-let [p (:publication doc)] (str " ∈ " (:name p)))
         (when (:draft doc) " (draft)"))))

(defn compact-publication
  "A publication rendered with its members (from `corpus`) indented beneath it, e.g.

      publication meta-graph@1.0 en <open> \"…\" (draft) — 2 members
        • ki reason-is-fuzzy@1.0 en [inference] \"…\" ⇐ fuzzy-confidence@1 ∈ meta-graph (draft)
        • article on-partial-knowledge@1.0 en [explainer] \"…\" ∈ meta-graph (draft)"
  [corpus pub]
  (let [ms (lineage/members corpus pub)]
    (str (compact-doc pub)
         " — "
         (count ms)
         " member"
         (when (not= 1 (count ms)) "s")
         (apply str (map #(str "\n  • " (compact-doc %)) ms)))))

(defn compact-corpus
  "The whole `corpus` as compact lines — one per current lineage version (publications last, with
  their members expanded), so a reader sees the graph at a glance."
  [corpus]
  (let [ls (lineage/latest-lineages corpus)
        {pubs true
         docs false}
        (group-by #(= "publication" (:type %)) ls)]
    (str/join "\n" (concat (map compact-doc docs) (map #(compact-publication corpus %) pubs)))))
