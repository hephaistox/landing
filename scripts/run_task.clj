(ns run-task
  (:require
   [babashka.process :refer [check process]]
   [clojure.string   :as str]))

(defn run-claude [prompt] (let [proc (process ["claude" prompt] {:inherit true})] (check proc)))

(defn read-file [f] (try (slurp f) (catch Exception _ "")))

(defn next-task
  [tasks-content]
  (some-> (some->> tasks-content
                   str/split-lines
                   (filter #(str/starts-with? % "- [ ]"))
                   first)
          (str/replace "- [ ] " "")))

(defn mark-done
  [tasks-content task]
  (str/replace tasks-content
               (re-pattern (java.util.regex.Pattern/quote (str "- [ ] " task)))
               (str "- [x] " task)))

(defn run
  []
  (let [tasks-content (read-file "tasks.md")
        context (read-file "context.md")
        task (next-task tasks-content)]
    (if (nil? task)
      (println "No pending tasks.")
      (do (println "Running task:" task)
          ;; Build prompt
          (let [prompt (str context "\n\nTask:\n" task "\n\nProvide a concrete solution.")]
            (run-claude prompt)
            ;; Call Claude CLI
            ;; Save log
            (spit (str "logs/" (System/currentTimeMillis) ".md") (str "# Task\n" task))
            ;; Mark task as done
            (spit "tasks.md" (mark-done tasks-content task))
            (println "Done."))))))
