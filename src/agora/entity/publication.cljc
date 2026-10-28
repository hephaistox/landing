(ns agora.entity.publication
  "Publication.

## Definition

A **publication** is a document of type `:publication` gathering a set of changes. It has a
title, a **text** (a sentence describing its change), a language, an owner and a
**status**:

- `:open`: it is still being written. Its documents are drafts.
- `:closed`: it is published. Its documents are published and frozen.
- `:canceled`: its owner gave it up. It keeps its drafts, frozen, never published: nothing
  is lost. Its name stays taken: the status is not part of the identity.

Every document that is not a publication belongs to **exactly one** publication: the one
that created it, its **origin publication** (`from-publication`). It never changes.

In an open publication, drafts **may cite each other**. That is the only exception to the
rule forbidding to cite a draft: they are published at the same time, so once the
publication is closed, no citation targets a draft any more. A draft cannot be cited from
another publication.

Inside the publication, a draft cites another draft by its **`:id`**: it has no minor yet,
its id is what ties it to the others. The citation is written `{:id \"…\"}` in `:inputs`,
and the link `[[id:…|label]]` in the text.

**Publishing** a publication means closing it. At that moment:

- every draft gets its minor: the one after the latest already published for the same
  major, or 0 when there is none. If another publication published a minor in between, it
  comes after it;
- every citation by `:id` becomes a citation by tnlr `(type, name, language, major)`, as every
  published citation.

A publication holds only documents **of its language**. The same publication exists in
several languages under one name, as any document. Its documents freely cite documents of
other languages, which it does not hold.

A publication **asserts nothing** and is not contested. What holds together is carried by
the documents citing each other: a deduction asserts that its conclusion follows from its
inputs, an article that its reasoning holds. Those documents are contested, not the
publication that published them. As any type, `:publication` is described by the document
`type-publication`.

A published document is frozen: its identity, owner and content never change. Only its
timestamp may be added, then completed. To **correct** it, its author publishes a new version of it
in a new publication. The former publication stays readable as it was.

A publication has only a **major**: no minor, no draft. Its status stands for the draft.
Open, it is edited in place, title included. Closed, it is frozen forever. It is corrected
by a new publication, so a minor would make no sense.

The owner of an open publication may **invite** other users to contribute: its
**contributors** (`:contributors`, each `{:id}`). Without an invitation, nobody else writes in it. Every
document names its own owner (`:owner`): who wrote it, the owner of the publication or a
contributor. The invitation holds for this publication only, not for its other majors, and
may be revoked.

While a publication is open, it keeps being edited. A **new major** is created only once
the last one is closed: two majors of a publication are never open at the same time.

A new major **takes up the same topic again**. Nobody cites a publication, so its major
does not signal a breaking change, as for the other documents: it marks a new take. Its
identity is:

```
(:publication, name, language, major)
```

## Goals

- Prepare several documents together, then publish them at once.
- Always know where each version comes from.
- Tell an evolution: one publication per step.
- Publish each language when it is ready, without waiting for the others.
- Write together, on invitation.
- Keep track of what was published, then corrected.

## Examples

The home page example fits in four closed publications, one per edition, each in `:fr`
and in `:en`:

| publication             | what it publishes                                 |
|-------------------------|---------------------------------------------------|
| `exemple-pedagogique-1` | the article 1.0                                   |
| `exemple-pedagogique-2` | the definition \"Rapide\" and the article 1.1     |
| `exemple-pedagogique-3` | the timing and the article 1.2                    |
| `exemple-pedagogique-4` | the deduction, the induction and the article 1.3  |

The two languages of the first edition:

```clojure
{:type :publication :name \"exemple-pedagogique-1\" :lang :fr :status :closed
 :documents [{:type :article :name \"ma-voiture-est-rapide\" :lang :fr
              :content {:title \"Ma voiture est rapide\" …}}]
 :content {:title \"Exemple pédagogique 1\" …}}
{:type :publication :name \"exemple-pedagogique-1\" :lang :en :status :closed
 :documents [{:type :article :name \"ma-voiture-est-rapide\" :lang :en
              :content {:title \"My car is fast\" …}}]
 :content {:title \"Teaching example 1\" …}}
```

\"La méthode scientifique\" is an open publication: its eight documents are drafts.

In that publication, the definition \"Énoncé singulier\" cites the definition \"Énoncé
universel\" by its id:

```clojure
{:type :definition :name \"enonce-singulier\"
 :content {:text \"un énoncé portant sur un individu ou un événement déterminé … Là où l'[[id:a526a25d-c823-4295-a8b4-0e6514d3768a]] porte sur une classe non bornée, …\"
           :inputs [{:id #uuid \"a526a25d-c823-4295-a8b4-0e6514d3768a\"}] …}}
```

Once published, that link becomes `[[definition:enonce-universel:fr@1]]`.

```clojure
{:type :publication :name \"la-methode-scientifique\" :lang :fr :status :open
 :owner {:id #uuid \"61a3d5a4-a94a-4e02-9e1e-0db6f5c03503\"}
 :documents [{:type :definition :content {:title \"Énoncé universel\" …}} …]
 :content {:title \"La méthode scientifique\" …}}
```

## Links

- `agora.entity.document`, `agora.value.type`: a publication is a document of type `:publication`.
- `agora.value.version`: open, it holds drafts; closed, published versions. Itself has only a major.
- `agora.value.identity`: its own has no minor.
- `agora.entity.person`: its owner.
- `agora.entity.user`: its contributors, invited by the owner.
- `agora.value.lang`: a publication has a language, the one of its documents.
- `agora.value.citation`: its documents cite beyond its language."
  (:require
   [agora.entity.document :as document]
   [agora.entity.person   :as person]
   [agora.entity.user     :as user]
   [agora.value.citation  :as citation]
   [agora.value.identity  :as identity]
   [agora.value.lang      :as lang]
   [agora.value.name      :as name]
   [agora.value.reference :as reference]
   [agora.value.timestamp :as timestamp]
   [agora.value.type      :as type]
   [agora.value.version   :as version]
   [auto-core.schema      :as schema]))

;; ---------------------------------------------------------------------------
;; Domain
;; ---------------------------------------------------------------------------

(def content-schema
  "The grammar of what a publication says: its title, the text describing its change, and
  its date."
  [:map {:closed true}
   [:title [:string {:min 1}]]
   [:text [:string {:min 1}]]
   [:published-at document/published-at-schema]])

(def schema
  "The grammar of a publication: a document of type `:publication`, with a major only, a
  status, its contributors, its documents, and the objections it answers (`:answers`)."
  [:map {:closed true}
   [:id :uuid]
   [:type [:= :publication]]
   [:name name/schema]
   [:lang lang/schema]
   [:major [:int {:min 1}]]
   [:status [:enum :open :closed :canceled]]
   [:owner reference/user-schema]
   [:contributors [:vector reference/user-schema]]
   [:answers {:optional true}
    [:vector reference/objection-schema]]
   [:documents [:vector document/schema]]
   [:content content-schema]])

(defn validate
  "nil when `publication` follows `schema`, else a structured error `{:error :schema :data}`."
  [publication]
  (schema/validate-data-humanize schema publication))

(defn open? [publication] (= :open (:status publication)))

(defn closed? [publication] (= :closed (:status publication)))

(defn canceled? [publication] (= :canceled (:status publication)))

(defn documents "Every document of `publications`." [publications] (mapcat :documents publications))

(defn published-version
  "The document with this `:id` in a closed publication of `publications`. nil when there
  is none, or when it is a draft."
  [publications {:keys [id]}]
  (some (fn [p] (when (closed? p) (some #(when (= id (:id %)) %) (:documents p)))) publications))

(defn from-publication
  "The publication `document` comes from, among `publications`: the one that created its version."
  [publications document]
  (some (fn [p] (when (some #(= (:id document) (:id %)) (:documents p)) p)) publications))

(defn update-version
  "The publication of `publications` holding `version`, with `f` applied to that version."
  [publications version f]
  (update (from-publication publications version)
          :documents
          (partial mapv #(if (= (:id version) (:id %)) (f %) %))))

(defn named-persons
  "Every person named in `publications`: owners, contributors, credited authors."
  [publications]
  (concat (keep :owner publications)
          (mapcat :contributors publications)
          (keep :owner (documents publications))
          (keep :author (documents publications))))

(defn save
  "`publications` with each of `changed` created, or replacing the one of the same `:id`."
  [publications changed]
  (let [ids (set (map :id changed))] (into (vec (remove #(ids (:id %)) publications)) changed)))

(defn publish
  "`publication` closed at `at`: each draft gets the next minor of its major, among the
  documents already published in `publications`, and every citation and link by `:id`
  becomes a citation by tnlr."
  [publication publications at]
  (let [published (filter version/published? (documents publications))
        by-id (into {} (map (fn [x] [(:id x) x])) (:documents publication))
        ->tnlr #(if (citation/by-id? %) (identity/tnlr (by-id (:id %))) %)]
    (-> publication
        (assoc :status :closed)
        (assoc-in [:content :published-at] at)
        (assoc :documents
               (mapv (fn [d]
                       (-> d
                           (assoc :minor (identity/next-minor published d))
                           (update :content
                                   #(-> %
                                        (assoc :published-at at)
                                        (update :text citation/replace-links ->tnlr)
                                        (update :inputs (partial mapv ->tnlr))))))
                     (:documents publication))))))

;; ---------------------------------------------------------------------------
;; Rules
;; ---------------------------------------------------------------------------

(defn- with-publication [publications] (for [p publications d (:documents p)] [p d]))

(def rules
  "Rules on a sequence of publications."
  [{:id :publication/shape
    :description
    "a publication follows its grammar: type :publication, a text, a language, a major and no minor, a status, an owner, its contributors and documents"
    :check (fn [publications]
             (for [p publications
                   :let [error (validate p)]
                   :when error]
               {:rule :publication/shape
                :subject p
                :detail (:error error)}))}
   {:id :publication/identity
    :description
    "the identity (name, language, major) is unique; the :id is unique among publications and documents"
    :check (fn [publications]
             (concat (for [[k ps] (group-by (juxt :name :lang :major) publications)
                           :when (> (count ps) 1)]
                       {:rule :publication/identity
                        :subject (first ps)
                        :detail k})
                     (for [[id xs] (group-by :id (concat publications (documents publications)))
                           :when (> (count xs) 1)]
                       {:rule :publication/identity
                        :subject (first xs)
                        :detail id})))}
   {:id :publication/content
    :description "a publication holds only documents of its language, never a publication"
    :check (fn [publications]
             (for [[p d] (with-publication publications)
                   :when (or (not= (:lang p) (:lang d)) (= :publication (:type d)))]
               {:rule :publication/content
                :subject d
                :detail (:name p)}))}
   {:id :publication/status
    :description "open or canceled ⇒ drafts; closed ⇒ published versions"
    :check (fn [publications]
             (for [[p d] (with-publication publications)
                   :when (not= (version/draft? d) (not (closed? p)))]
               {:rule :publication/status
                :subject d
                :detail (:status p)}))}
   {:id :publication/majors
    :description "majors go 1..n; a new major only after a closed one"
    :check (fn [publications]
             (for [[k ps] (group-by (juxt :name :lang) publications)
                   :let [ps (sort-by :major ps)]
                   :when (or (not= (map :major ps) (range 1 (inc (count ps))))
                             (some open? (butlast ps)))]
               {:rule :publication/majors
                :subject (first ps)
                :detail [k (map (juxt :major :status) ps)]}))}
   {:id :publication/one-version
    :description "two versions of a document are never in the same publication"
    :check (fn [publications]
             (for [p publications
                   [r ds] (group-by identity/tnlr (:documents p))
                   :when (> (count ds) 1)]
               {:rule :publication/one-version
                :subject p
                :detail r}))}
   {:id :publication/citations
    :description "published versions are cited by tnlr, a draft of the same publication by :id"
    :check (fn [publications]
             (let [docs (documents publications)]
               (for [[p d] (with-publication publications)
                     c (-> d
                           :content
                           :inputs)
                     :let [problem (if (citation/by-id? c)
                                     (let [t (some #(when (= (:id c) (:id %)) %) (:documents p))]
                                       (when-not (and t (version/draft? d) (version/draft? t))
                                         :id-outside-drafts))
                                     (let [targets (filter #(= c (identity/tnlr %)) docs)]
                                       (cond
                                         (empty? targets) :unknown
                                         (some #(and (version/draft? %) (some #{%} (:documents p)))
                                               targets)
                                         :draft-by-tnlr
                                         (every? version/draft? targets) :draft-elsewhere)))]
                     :when problem]
                 {:rule :publication/citations
                  :subject d
                  :detail [problem c]})))}])

(def person-rules
  "Rules on `publications`, with the `persons` and the `users`."
  [{:id :publication/persons
    :description "every named person's :id is a person's"
    :check (fn [publications persons _users]
             (let [known (set (map (fn [p] {:id (:id p)}) persons))]
               (for [p (distinct (named-persons publications))
                     :when (not (known p))]
                 {:rule :publication/persons
                  :subject p})))}
   {:id :publication/owner
    :description
    "a publication and each of its documents are owned by a user; a document's owner is the publication's, or an invited contributor"
    :check (fn [publications _persons users]
             (let [user? (set (map (fn [u] {:id (:id u)}) users))]
               (concat (for [p publications
                             :when (not (user? (:owner p)))]
                         {:rule :publication/owner
                          :subject p
                          :detail (:owner p)})
                       (for [[p d] (with-publication publications)
                             :when (or (not (user? (:owner d)))
                                       (not (or (= (:owner d) (:owner p))
                                                (some #{(:owner d)} (:contributors p)))))]
                         {:rule :publication/owner
                          :subject d
                          :detail (:owner d)}))))}
   {:id :publication/contributors
    :description "an invited contributor is a user"
    :check (fn [publications _persons users]
             (let [ids (set (map :id users))]
               (for [p publications
                     c (:contributors p)
                     :when (not (ids (:id c)))]
                 {:rule :publication/contributors
                  :subject p
                  :detail c})))}])

(defn violations
  "Every rule broken by `publications`, with the `persons` and the `users`."
  [publications persons users]
  (let [docs (documents publications)
        apply-rules (fn [rules input] (mapcat #((:check %) input) rules))]
    (concat (apply-rules person/rules persons)
            (mapcat #((:check %) persons users) user/rules)
            (mapcat #((:check %) publications persons users) person-rules)
            (apply-rules (concat lang/rules
                                 document/rules
                                 type/rules
                                 version/rules
                                 identity/rules
                                 citation/rules
                                 timestamp/rules)
                         docs)
            (apply-rules rules publications))))

(def transition-rules
  "Rules on `publications` before `changed` are saved."
  [{:id :publication/frozen
    :description
    "a closed or canceled publication never changes, but for the timestamps of its versions"
    :check (fn [publications changed]
             (let [before (into {} (map (fn [x] [(:id x) x])) publications)
                   frozen (fn [p] (update p :documents (partial mapv #(dissoc % :timestamp))))]
               (for [p changed
                     :let [old (before (:id p))]
                     :when (and (or (closed? old) (canceled? old)) (not= (frozen old) (frozen p)))]
                 {:rule :publication/frozen
                  :subject p})))}
   {:id :publication/no-move
    :description "a document never leaves its publication"
    :check (fn [publications changed]
             (let [after (save publications changed)
                   home (fn [ps] (into {} (for [p ps d (:documents p)] [(:id d) (:id p)])))
                   home-after (home after)]
               (for [[id pid] (home publications)
                     :when (not= pid (home-after id))]
                 {:rule :publication/no-move
                  :subject {:id id}})))}])

(defn transition-violations
  "Every rule broken when `changed` publications are saved into `publications`."
  [publications changed]
  (mapcat #((:check %) publications changed) transition-rules))
