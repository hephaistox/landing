(ns build
  "Builds the standalone jar: `clojure -T:build uber`.

  The frontend must be released first (`npx shadow-cljs release app-admin`), so that
  `resources/public/js/compiled/app-admin.js` exists and gets bundled."
  (:require
   [clojure.java.io         :as io]
   [clojure.pprint          :as pprint]
   [clojure.string          :as str]
   [clojure.tools.build.api :as b])
  (:import (java.io File)
           (java.security MessageDigest)))

(def class-dir "target/classes")
(def uber-file "target/landing.jar")

(defn clean [_] (b/delete {:path "target"}))

;; ---------------------------------------------------------------------------
;; Asset fingerprinting
;; ---------------------------------------------------------------------------
;; CSS/JS are served `immutable` for a year (`landing.endpoints.resource`). Each referenced
;; one is renamed after a hash of its content (`custom.css` -> `custom.34f0a84f.css`) and the
;; pages point at the new name. A changed file gets a new URL; an unchanged one stays cached.

(defn- sha8
  "First 8 hex chars of the SHA-256 of `data`."
  [^bytes data]
  (->> (.digest (MessageDigest/getInstance "SHA-256") data)
       (take 4)
       (map #(format "%02x" (bit-and % 0xff)))
       (apply str)))

(defn- fingerprinted-name
  "`custom.css` + hash -> `custom.<hash>.css`."
  [filename hash]
  (let [dot (str/last-index-of filename ".")]
    (str (subs filename 0 dot) "." hash (subs filename dot))))

(defn- rel-path
  "Path of `file` relative to `root`, with `/` separators."
  [^File root ^File file]
  (-> (.relativize (.toPath root) (.toPath file))
      str
      (str/replace File/separator "/")))

(def ^:private ref-re "A local `href`/`src` attribute value." #"(href|src)=\"([^\"#?:]+)\"")

(def ^:private base-re #"<base\s+href=\"([^\"]+)\"")

(defn- resolve-ref
  "Public path (`css/custom.css`) that `ref` points at, from a page at `page-path` (`fr/index.html`),
  honoring the page's `<base href>`."
  [html page-path ref]
  (let [base (or (second (re-find base-re html)) (str "/" page-path))
        url (.resolve (java.net.URI. (str "http://x" base)) ref)]
    (subs (.getPath url) 1)))

(defn- rewrite-page
  "Point every reference of `html` to a renamed asset at its fingerprinted name."
  [html page-path renames]
  (str/replace
   html
   ref-re
   (fn [[whole attr ref]]
     (if-let [to (get renames (resolve-ref html page-path ref))]
       (str attr
            "=\""
            (str/replace ref #"[^/]+$" (str/re-quote-replacement (last (str/split to #"/"))))
            "\"")
       whole))))

(def ^:private manifest-script-re
  "The `<script id=\"cache-bust-manifest\">` placeholder of the admin page."
  #"(?s)(<script id=\"cache-bust-manifest\"[^>]*>).*?(</script>)")

(defn- json-map
  "`{\"a\" \"b\"}` as a JSON object. Keys and values are plain asset paths."
  [m]
  (str "{" (str/join "," (map (fn [[k v]] (str "\"" k "\":\"" v "\"")) (sort m))) "}"))

(defn- fingerprint-assets!
  "Fingerprint the CSS/JS under `public-dir` referenced by a page, and rewrite the pages.
  Returns the rename map `{\"css/custom.css\" \"css/custom.34f0a84f.css\"}`."
  [public-dir]
  (let [public (io/file public-dir)
        files (filter #(.isFile ^File %) (file-seq public))
        pages (into {}
                    (for [f files
                          :when (str/ends-with? (.getName ^File f) ".html")]
                      [(rel-path public f) (slurp f)]))
        referenced
        (set (for [[page html] pages [_ _ ref] (re-seq ref-re html)] (resolve-ref html page ref)))
        renames (into {}
                      (for [f files
                            :let [path (rel-path public f)]
                            :when (and (re-find #"(?i)\.(css|js)$" path)
                                       (not (re-find #"\.[0-9a-f]{8}\.(css|js)$" path))
                                       (referenced path))]
                        (let [data (java.nio.file.Files/readAllBytes (.toPath ^File f))
                              to (str/replace path
                                              #"[^/]+$"
                                              (fingerprinted-name (.getName ^File f) (sha8 data)))]
                          (.renameTo ^File f (io/file public to))
                          [path to])))]
    (doseq [[page html] pages
            :let [updated (cond-> (rewrite-page html page renames)
                            (str/ends-with? page "all-kind-of-checks.html")
                            (str/replace manifest-script-re
                                         (fn [[_ open close]]
                                           (str open (json-map renames) close))))]
            :when (not= html updated)]
      (spit (io/file public page) updated))
    renames))

;; ---------------------------------------------------------------------------
;; Jar
;; ---------------------------------------------------------------------------

(defn uber
  "Compile the server and pack it, with its dependencies and the static site, in `target/landing.jar`."
  [_]
  (clean nil)
  (let [basis (b/create-basis {:project "deps.edn"
                               :aliases [:env-prod]})]
    (b/copy-dir {:src-dirs ["src" "resources" "env/prod"]
                 :target-dir class-dir})
    (let [renames (fingerprint-assets! (str class-dir "/public"))]
      ;; asset paths also live in compiled data (`landing.pages.admin`); the w3c-validate handler
      ;; reads this map to find the fingerprinted URL
      (spit (io/file class-dir "cache_bust_manifest.edn") (with-out-str (pprint/pprint renames)))
      (println "Fingerprinted assets:" (count renames)))
    (b/compile-clj {:basis basis
                    :ns-compile '[landing.server]
                    :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis basis
             :main 'landing.server})
    (println "Jar built:" uber-file)))
