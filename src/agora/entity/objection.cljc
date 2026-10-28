(ns agora.entity.objection
  "Objection.

## Definition

An **objection** is a special publication, of type `:objection`. Its text says
what it objects to, and its `:documents` designate **one single published version**, by
its `:id`: the one it wants to see evolve. It asks the owner to change it. It targets one
document, never a set: a whole graph is not contested.

It escapes the rule \"a version belongs to one publication\": the version it designates
stays the one of its origin publication. As a publication, it has a language, a major, an
owner and a **status**:

- `:draft`: it awaits an answer;
- `:closed`: it has been dealt with.

Any user may object. An objection is written in the language of the document it targets.

The owner of the targeted document answers it with a publication designating it by its
`:id` (`:answers`) and holding a new version of the document. When that publication is
published, the objection is closed: **closed means a change was made**. The author of the
objection may then react to that change by opening the next major of the objection,
targeting the new version. A new major is opened only once the previous one is closed.

## Goals

- Contest a precise document, on its own ground, and obtain a change.
- Keep track of the discussion: each major of the objection answers a version of the
  document.
- Prevent contesting whole parts of the graph at once.

## Examples

The home page example: an active reader (Hephaistox2) objects after each edition of \"Ma
voiture est rapide\", and the next edition answers him.

```clojure
  {:type :objection
   :name \"que-veut-dire-rapide\"
   :lang :fr
   :major 1
   :owner {:id #uuid \"8e2ad0d5-c936-49ca-ada3-9e7dd84d13a9\"}
   :status :closed
   :documents [{:id #uuid \"dddddddd-0000-0000-0000-000020010100\"}]
   :content {:title \"Que veut dire « rapide » ?\"
             :text \"« Rapide », ça veut dire quoi, au juste ?\"
             :reason :omission …}}
;; the edition 1.0, in fr
```

The publication `exemple-pedagogique-2` answers it (`:answers`) and holds the edition 1.1,
which defines \"rapide\". Once it is published, the objection is closed.

\"Et le modèle en général ?\" is closed by the edition 1.3. The reader reacts to that change:
the major 2 of his objection, a `:draft`, designates the edition 1.3 and says that going
from one car to the whole model is a leap.

## Links

- `agora.value.identity`: an objection targets a precise version, minor included.
- `agora.entity.user`: its author is a user.
- `agora.entity.publication`: it is a kind of publication; a publication answers it.

`violations` applies the rules of every concept, objections included."
  (:require
   [agora.entity.document    :as document]
   [agora.entity.publication :as publication]
   [agora.value.citation     :as citation]
   [agora.value.lang         :as lang]
   [agora.value.name         :as name]
   [agora.value.reference    :as reference]
   [agora.value.type         :as type]
   [agora.value.version      :as version]
   [auto-core.schema         :as schema]))

;; ---------------------------------------------------------------------------
;; Domain
;; ---------------------------------------------------------------------------

(def content-schema
  "The grammar of what an objection says: its title, its text, the `:reason` it invokes,
  the documents backing it (`:inputs`), and its date."
  [:map {:closed true}
   [:title [:string {:min 1}]]
   [:text [:string {:min 1}]]
   [:reason :keyword]
   [:inputs {:optional true}
    [:vector citation/schema]]
   [:published-at document/published-at-schema]])

(def schema
  "The grammar of an objection: a publication of type `:objection`, with a major only, a
  status, an owner, the single version it targets (`:documents`), and what it says
  (`:content`)."
  [:map {:closed true}
   [:id :uuid]
   [:type [:= :objection]]
   [:name name/schema]
   [:lang lang/schema]
   [:major version/major-schema]
   [:status [:enum :draft :closed]]
   [:owner reference/user-schema]
   [:documents
    [:vector {:min 1
              :max 1}
     reference/version-schema]]
   [:content content-schema]])

(defn validate
  "nil when `objection` follows `schema`, else a structured error `{:error :schema :data}`."
  [objection]
  (schema/validate-data-humanize schema objection))

(defn closed? [objection] (= :closed (:status objection)))

(defn draft? [objection] (= :draft (:status objection)))

(defn target
  "The published version `objection` wants to change.

  Assumption: Only one document is defined in `:documents` as an objection is made like this."
  [objection]
  (first (:documents objection)))

(defn- answering
  "Publications declaring they answer `objection`."
  [publications objection]
  (filter (fn [p] (some #(= (:id objection) (:id %)) (:answers p))) publications))

(defn save
  "`objections` with each of `changed` created, or replacing the one of the same `:id`."
  [objections changed]
  (publication/save objections changed))

(defn publish
  "`publication` published at `at`, with every objection it answers closed.
  Returns `{:publication :objections}`, the objections being only those it closes."
  [publication publications objections at]
  {:publication (publication/publish publication publications at)
   :objections (vec (for [o objections
                          :when (some #(= (:id o) (:id %)) (:answers publication))]
                      (assoc o :status :closed)))})

;; ---------------------------------------------------------------------------
;; Rules
;; ---------------------------------------------------------------------------

(defn- target-version
  "`[publication document]`: the version `objection` targets, and the publication it comes from."
  [publications objection]
  (let [t (target objection)
        p (publication/from-publication publications t)]
    [p (some #(when (= (:id t) (:id %)) %) (:documents p))]))

(defn- newer?
  "Is `document` a later version of the lineage of `target`? A draft of the same or a later
  major is: it gets its minor when published."
  [target document]
  (and (= (select-keys target [:type :name :lang]) (select-keys document [:type :name :lang]))
       (if (nil? (:minor document))
         (>= (:major document) (:major target))
         (pos? (compare [(:major document) (:minor document)] [(:major target) (:minor target)])))))

(def rules
  "Rules on `publications`, `objections` and `users`."
  [{:id :objection/shape
    :description
    "an objection follows its grammar: type :objection, a language, a major and no minor, a title, a text, a :reason, a status :draft or :closed, an owner, a single {:id} targeted, its backing citations"
    :check (fn [_publications objections _users]
             (for [o objections
                   :let [error (validate o)]
                   :when error]
               {:rule :objection/shape
                :subject o
                :detail (:error error)}))}
   {:id :objection/owner
    :description "the author of an objection is a user"
    :check (fn [_publications objections users]
             (let [ids (set (map :id users))]
               (for [o objections
                     :when (not (ids (:id (:owner o))))]
                 {:rule :objection/owner
                  :subject o
                  :detail (:owner o)})))}
   {:id :objection/target
    :description
    "an objection designates by its :id a published version of a document, in its language"
    :check (fn [publications objections _users]
             (for [o objections
                   :let [t (target o)
                         [_ d] (target-version publications o)]
                   :when (or (nil? d) (version/draft? d) (not= (:lang o) (:lang d)))]
               {:rule :objection/target
                :subject o
                :detail (:id t)}))}
   {:id :objection/reason
    :description
    "an objection invokes an objection its target's type predefines, and cites only documents of the types that may back it"
    :check (fn [publications objections _users]
             (for [o objections
                   :let [[_ d] (target-version publications o)
                         predefined (when d
                                      (type/objection-of (:type d)
                                                         (-> o
                                                             :content
                                                             :reason)))
                         backing (remove #(contains? (:backed-by predefined) (:type %))
                                         (-> o
                                             :content
                                             :inputs))]
                   :when (and d (or (nil? predefined) (seq backing)))]
               {:rule :objection/reason
                :subject o
                :detail (if predefined
                          (mapv :type backing)
                          [(:type d)
                           (-> o
                               :content
                               :reason)])}))}
   {:id :objection/identity
    :description
    "the identity (name, language, major) is unique; majors go 1..n; a new major only after a closed one"
    :check (fn [_publications objections _users]
             (concat (for [[k os] (group-by (juxt :name :lang :major) objections)
                           :when (> (count os) 1)]
                       {:rule :objection/identity
                        :subject (first os)
                        :detail k})
                     (for [[k os] (group-by (juxt :name :lang) objections)
                           :let [os (sort-by :major os)]
                           :when (or (not= (map :major os) (range 1 (inc (count os))))
                                     (some draft? (butlast os)))]
                       {:rule :objection/identity
                        :subject (first os)
                        :detail [k (map (juxt :major :status) os)]})))}
   {:id :objection/answer
    :description
    "an answer targets a known objection, by a publication of the targeted document's owner that changes it"
    :check (fn [publications objections _users]
             (concat (for [p publications
                           a (:answers p)
                           :when (not (some #(= (:id a) (:id %)) objections))]
                       {:rule :objection/answer
                        :subject p
                        :detail a})
                     (for [o objections
                           :let [[_ td] (target-version publications o)]
                           :when td
                           p (answering publications o)
                           :when (or (not= (:owner p) (:owner td))
                                     (not (some #(newer? td %) (:documents p))))]
                       {:rule :objection/answer
                        :subject o
                        :detail (:name p)})))}
   {:id :objection/status
    :description "an objection is closed exactly when a closed publication answers it"
    :check (fn [publications objections _users]
             (for [o objections
                   :let [answered? (some publication/closed? (answering publications o))]
                   :when (not= (closed? o) (boolean answered?))]
               {:rule :objection/status
                :subject o}))}])

(defn violations
  "Every rule broken by `publications` and `objections`, with the `persons` and the `users`."
  [publications objections persons users]
  (concat (publication/violations publications persons users)
          (mapcat #((:check %) publications objections users) rules)))

(def transition-rules
  "Rules on `objections` before `changed` are saved."
  [{:id :objection/frozen
    :description "a closed objection never changes"
    :check (fn [objections changed]
             (let [before (into {} (map (fn [o] [(:id o) o])) objections)]
               (for [o changed
                     :let [old (before (:id o))]
                     :when (and (closed? old) (not= old o))]
                 {:rule :objection/frozen
                  :subject o})))}])

(defn transition-violations
  "Every rule broken when `changed-publications` and `changed-objections` are saved."
  [publications objections changed-publications changed-objections]
  (concat (publication/transition-violations publications changed-publications)
          (mapcat #((:check %) objections changed-objections) transition-rules)))
