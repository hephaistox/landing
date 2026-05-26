(ns cache-bust
  "Content-hash fingerprinting for static CSS/JS, applied to the *built jar*
  (never the source tree) just before a `bb la` / `bb prod` push.

  Browsers cache our CSS/JS with `Cache-Control: max-age=31536000, immutable`
  (see `landing.endpoints.resource`), so a returning visitor would keep a stale
  file for a year after a deploy. `fingerprint-jar!` opens the uberjar, renames
  each referenced asset to embed a short SHA-256 of its contents
  (`custom.css` -> `custom.34f0a84f.css`) and rewrites every matching link in
  the bundled HTML, so a changed file gets a brand-new URL the browser is
  forced to fetch while unchanged files keep their hash and stay cached.

  Because this runs on the packaged artifact, the working tree is never
  touched, and `js/compiled/app-admin.js` (built by `shadow-cljs release`
  earlier in the build) is fingerprinted too. Only assets actually referenced
  by some bundled HTML page are processed; fonts/images referenced from inside
  CSS via relative `url(...)` keep their directory, so they still resolve."
  (:require
   [babashka.process :refer [shell]]
   [cheshire.core    :as json]
   [clojure.pprint   :as pprint]
   [clojure.string   :as str])
  (:import (java.nio.file CopyOption
                          FileSystems
                          FileVisitOption
                          Files
                          LinkOption
                          OpenOption
                          Path
                          Paths
                          StandardCopyOption)
           (java.security MessageDigest)))

(def ^:private asset-ext-re #"(?i)\.(?:css|js)$")
(def ^:private fingerprint-re #"\.[0-9a-f]{8}\.(?:css|js)$")

(defn- sha8
  "First 8 hex chars of the SHA-256 of `bytes`."
  [^bytes bytes]
  (->> (.digest (MessageDigest/getInstance "SHA-256") bytes)
       (take 4)
       (map #(format "%02x" (bit-and % 0xff)))
       (apply str)))

(defn- regular-files
  "All regular files under `root` (a zip-fs path), as a vector."
  [^Path root]
  (with-open [stream (Files/walk root (make-array FileVisitOption 0))]
    (->> (.iterator stream)
         iterator-seq
         (filter #(Files/isRegularFile % (make-array LinkOption 0)))
         vec)))

(def ^:private manifest-script-re
  "The placeholder `<script id=\"cache-bust-manifest\">…</script>` in the admin
  shell, whose body we replace with the JSON manifest so the admin SPA can
  resolve fingerprinted asset URLs client-side."
  #"(?s)(<script id=\"cache-bust-manifest\"[^>]*>).*?(</script>)")

(defn- inject-manifest
  "Replace the body of the cache-bust-manifest <script> in `html` with `json`.
  Returns `html` unchanged when the placeholder is absent."
  [html json]
  (str/replace html manifest-script-re (fn [[_ open close]] (str open json close))))

(defn- read-str [^Path p] (String. (Files/readAllBytes p) "UTF-8"))
(defn- write-str
  [^Path p ^String s]
  (Files/write p (.getBytes s "UTF-8") (make-array OpenOption 0)))

(defn- fingerprinted-name
  "`custom.css` + hash -> `custom.<hash>.css`."
  [filename hash]
  (let [dot (str/last-index-of filename ".")]
    (str (subs filename 0 dot) "." hash (subs filename dot))))

(defn fingerprint-jar!
  "Fingerprint referenced CSS/JS assets inside the uberjar at `jar-path` and
  rewrite the bundled HTML links to match. Mutates the jar in place."
  [jar-path]
  (println "cache-bust: fingerprinting assets inside" jar-path)
  (let [jar (Paths/get jar-path (make-array String 0))]
    (with-open [fs (FileSystems/newFileSystem jar {})]
      (let [public (.getPath fs "/public" (make-array String 0))
            files (regular-files public)
            htmls (filter #(str/ends-with? (str %) ".html") files)
            contents (into {} (map (fn [h] [h (read-str h)]) htmls))
            ;; URL tail as it appears in links, e.g. "css/custom.css".
            tail (fn [^Path p] (str (.relativize public p)))
            ;; CSS/JS that isn't already fingerprinted and is referenced by
            ;; at least one bundled HTML page.
            assets (for [p files
                         :let [n (str (.getFileName p))]
                         :when (and (re-find asset-ext-re n)
                                    (not (re-find fingerprint-re n))
                                    (let [t (tail p)] (some #(str/includes? % t) (vals contents))))]
                     p)
            renames
            (doall (for [p assets
                         :let [bytes (Files/readAllBytes p)
                               new-name (fingerprinted-name (str (.getFileName p)) (sha8 bytes))
                               new-p (.resolveSibling p new-name)
                               from (tail p)
                               to (tail new-p)]]
                     (do (Files/move p
                                     new-p
                                     (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
                         (println "  " from "->" to)
                         [from to])))
            ;; Replace longest tails first so no tail is a prefix of another.
            replacements (sort-by (comp count first) > renames)]
        (let [manifest-json (json/generate-string (into {} renames))]
          (doseq [[^Path h content] contents
                  :let [renamed
                        (reduce (fn [s [from to]] (str/replace s from to)) content replacements)
                        ;; The admin shell carries a manifest placeholder so the
                        ;; SPA can fingerprint asset URLs (e.g. external W3C
                        ;; validator links) without a round-trip.
                        updated (if (str/ends-with? (str h) "all-kind-of-checks.html")
                                  (inject-manifest renamed manifest-json)
                                  renamed)]
                  :when (not= content updated)]
            (write-str h updated)))
        ;; HTML links are rewritten above, but asset paths also live in compiled
        ;; data (e.g. landing.pages.admin/w3c-validate-css), which fingerprinting
        ;; cannot touch. Emit the rename map so runtime code (the w3c-validate
        ;; handler) can resolve the fingerprinted URL instead of 404ing.
        (write-str (.getPath fs "/cache_bust_manifest.edn" (make-array String 0))
                   (with-out-str (pprint/pprint (into {} renames))))
        (println "cache-bust:"
                 (count renames)
                 "asset(s) fingerprinted,"
                 (count htmls)
                 "HTML page(s) scanned")))))

(defn- push!
  "Commit the (now fingerprinted) jar in `target-dir` and force-push it. The
  repo, `clever` remote and `master` branch are set up by the auto-build
  `build` step; we amend its commit so a single commit is pushed."
  [target-dir]
  (println "cache-bust: committing fingerprinted jar and pushing…")
  (shell {:dir target-dir} "git" "add" "-A")
  (shell {:dir target-dir} "git" "commit" "--amend" "--no-edit")
  (shell {:dir target-dir} "git" "push" "--force" "clever" "master"))

(defn deploy!
  "Build the uberjar (no push), fingerprint its assets, then push. `build-fn`
  is `auto-build.tasks.web.prod-build/build`; it builds into `target-dir` and
  sets up the git repo but does not push. Returns the build exit code; skips
  fingerprint + push if the build failed."
  [build-fn printers current-task env-varname target-dir]
  (let [exit-code (build-fn printers
                            "."
                            current-task
                            [:env-prod :uberjar]
                            ["app-admin"]
                            env-varname
                            target-dir)]
    (if (and (number? exit-code) (zero? exit-code))
      (do (fingerprint-jar! (str target-dir "/landing.jar")) (push! target-dir) exit-code)
      (do (println "cache-bust: build failed (exit" exit-code "); skipping fingerprint + push")
          exit-code))))
