#!/usr/bin/env bb
;; Fragment injection for the static site.
;;
;; `update-website`: for each page in resources/public/{fr,en}, replace
;; the content between BEGIN/END markers with the matching fragment from
;; resources/fragments/, substituting {LANG_ROOT} based on the page's
;; depth from the language root.

(ns fragments
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def public-root "resources/public")
(def frag-root   "resources/fragments")

(def langs ["fr" "en"])

(def fragments-spec
  {"HEADER"    {}
   "FOOTER"    {}
   "LEFT-MENU" {}})

(defn page-depth
  "How many directories deep `page` lives under <public-root>/<lang>/."
  [public-rel]
  (let [parts (str/split public-rel #"/")]
    (- (count parts) 2)))

(defn lang-root-prefix [depth]
  (apply str (repeat depth "../")))

(defn substitute-lang-root [s prefix]
  (str/replace s "{LANG_ROOT}" prefix))

(defn replace-between-markers
  "Replace content between <!-- BEGIN:NAME --> and <!-- END:NAME --> in `s`
  with `replacement`. Markers are kept. If markers are absent, returns `s`."
  [s frag-name replacement]
  (let [pat (re-pattern (str "(?s)(<!-- BEGIN:" frag-name " -->\\n?).*?(<!-- END:" frag-name " -->)"))
        repl (java.util.regex.Matcher/quoteReplacement
              (str replacement (when-not (str/ends-with? replacement "\n") "\n")))]
    (str/replace s pat (str "$1" repl "$2"))))

(defn update-page!
  [page-path lang]
  (let [content (slurp (str page-path))
        rel    (str (fs/relativize public-root page-path))
        depth  (page-depth rel)
        prefix (lang-root-prefix depth)]
    (loop [content content
           [[name _] & rest] (seq fragments-spec)]
      (if name
        (let [frag-file (fs/path frag-root (str (str/lower-case name) "." lang ".html"))]
          (if (fs/exists? frag-file)
            (let [frag (-> (slurp (str frag-file))
                           (substitute-lang-root prefix))]
              (recur (replace-between-markers content name frag) rest))
            (recur content rest)))
        (when (not= content (slurp (str page-path)))
          (spit (str page-path) content)
          (println "  updated" rel))))))

(defn update-website! []
  (println "update-website: injecting fragments…")
  (doseq [lang langs
          page (fs/glob (fs/path public-root lang) "**.html")]
    (update-page! page lang)))

(update-website!)
