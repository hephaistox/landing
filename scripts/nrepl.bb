#!/usr/bin/env bb
;; Evaluate a form in the running dev nREPL and print what it returns — the REPL the app is actually
;; loaded in, rather than a fresh JVM that would only ever confirm what is on disk.
;;
;;   bb scripts/nrepl.bb <file.clj>
;;   echo '(+ 1 2)' | bb scripts/nrepl.bb /dev/stdin
;;
;; The port comes from `.nrepl-port`, written by the REPL on start. Output (`out`/`err`) is streamed
;; as it arrives, the value printed last.
(require '[babashka.deps :as deps] '[clojure.string :as str])
(deps/add-deps '{:deps {nrepl/bencode {:mvn/version "1.1.0"}}})
(require '[bencode.core :as b])
(import '[java.net Socket])
(defn- s->str [x] (cond (bytes? x) (String. ^bytes x) (coll? x) (mapv s->str x) :else x))
(let [port (Integer/parseInt (str/trim (slurp ".nrepl-port")))
      sock (Socket. "127.0.0.1" port)
      out  (java.io.PushbackInputStream. (.getInputStream sock))
      in   (.getOutputStream sock)]
  (b/write-bencode in {"op" "eval" "code" (slurp (first *command-line-args*))})
  (loop []
    (let [m (update-vals (b/read-bencode out) s->str)]
      (some->> (get m "out") print)
      (some->> (get m "err") print)
      (some->> (get m "value") println)
      (some->> (get m "ex") (println "EX:"))
      (flush)
      (if (some #{"done"} (get m "status")) (.close sock) (recur)))))
