(ns landing.agora.publication
  "Publications — the work-package that gathers a user's drafts and publishes them together. A
  publication is a `type=publication` document lineage in AGORA_DOCUMENT with the same lifecycle as
  any document: identity is its stable `name` (a cid), it is versioned by minor, and a rename is a
  new minor. It carries no epistemic kind; its `content` is `{:title :status :author :owner-id
  :published-at}` and it is `draft` while its status is `:open`.

  A publication has **no content language** — it is a container, resolved by its cid alone, and the
  drafts it gathers may be in different languages. The `lang` identity column (NOT NULL, shared with
  real documents) is therefore filled with a neutral placeholder (`lang-na`), never a real language,
  so nothing reads it as \"this publication is French\".

  Opening one is the simplest write — no inputs, so no pins or successor edges, just one row — and is
  reusable: an endpoint opens one manually today, and other authoring contexts (start authoring, an
  objection) will open one automatically."
  (:require
   [clojure.string            :as str]
   [landing.agora.db.document :as db-doc])
  (:import (java.time Instant)
           (java.time.temporal ChronoUnit)
           (java.util UUID)))

(def ^:private cid-alphabet "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")

(def ^:private lang-na
  "Placeholder for the NOT-NULL `lang` identity column: a publication has no content language, so its
  lang is `:zz` (ISO 639 private range) — never a real code, never read for resolution or display."
  :zz)

(defn- gen-cid
  "A random 10-char base62 cid — the opaque, stable lineage name, never derived from the title so a
  rename never dangles a reference."
  []
  (apply str (repeatedly 10 #(rand-nth cid-alphabet))))

(defn- now-iso [] (str (.truncatedTo (Instant/now) ChronoUnit/SECONDS)))

(defn- view
  "Endpoint view of a publication: its stable cid as `:id` (references survive a rename), the
  authored fields, and `:owner-id` exposed as `:author-id` (as for documents)."
  [{:keys [name title status author owner-id published-at]}]
  {:id name
   :type :publication
   :title title
   :status status
   :author author
   :author-id owner-id
   :published-at published-at})

(defn create!
  "Open a new publication owned by `owner-id` (display name `author`), titled `title`. Mints a cid,
  status `:open`, `draft` while open, created outside any publication. A publication has no content
  language (`lang-na`). Reusable — an endpoint or any other authoring context can call it. Returns
  the view."
  [owner-id author title]
  (let [cid (gen-cid)
        now (now-iso)
        content {:title title
                 :status :open
                 :author author
                 :owner-id owner-id
                 :published-at now}]
    (db-doc/insert! {:id (str (UUID/randomUUID))
                     :type :publication
                     :name cid
                     :lang lang-na
                     :major 1
                     :minor 0
                     :draft true
                     :content content
                     :computed {:pins []}
                     :published-at now
                     :publication-id nil})
    (view (assoc content :name cid))))

(defn fetch
  "The publication lineage `cid` (its latest minor, drafts included) as a view, or nil."
  [cid]
  (some-> (db-doc/fetch-latest-any :publication cid)
          view))

(defn list-open
  "The `owner`'s open publications, newest first, optionally filtered by `q` (title substring)."
  [owner q]
  (let [q (some-> q
                  str/trim
                  str/lower-case)]
    (into []
          (comp (filter (fn [p]
                          (and (= owner (:owner-id p))
                               (= :open (:status p))
                               (or (str/blank? q)
                                   (str/includes? (str/lower-case (str (:title p))) q)))))
                (map view))
          (db-doc/latest-any-of-type :publication))))
