(ns agora.value.citation
  "Citation and link.

## Definition

A **citation** says that a document relies on another one. A document lists its citations
in `:inputs`: by tnlr, or by id for a draft of the same publication.

A **link** is a citation placed in the text, where it is used:

```
[[type:name:language@major|label]]
```

The type, the name, the language and the major are mandatory: a link always carries a
complete tnlr. The label is optional; without it, the title of the cited document is
shown.

Every link is a citation. A citation is not necessarily a link: one may rely on a document
without naming it in the sentence.

Only **published** versions are cited: a draft still changes, one cannot rely on it.

A citation may target a document in **another language**. The tnlr carries the
language of the cited document.

## Goals

- Make visible **what a writing rests on**.
- Go back from a claim to its supports, in one click.
- Write readable prose that keeps its supports.
- Cite a thought without freezing it to a minor: minors break nothing, so a citation stays
  valid when the cited document improves.
- Rely on what exists in another language, without translating it first.

## Examples

The edition 1.1 of the home page article cites the definition of \"rapide\" and places it
in its sentence:

```clojure
  {:type :article
   :name \"ma-voiture-est-rapide\"
   :lang :fr
   :major 1
   :minor 1
   :content {:text \"Ma voiture est rapide. Par « rapide », j'entends atteindre 100 km/h en moins de 5 secondes ([[definition:rapide:fr@1|définition]]).\"
             :inputs [{:type :definition :name \"rapide\" :lang :fr :major 1}] …}}
```

The illustration \"Énoncé universel\" cites the definition it illustrates, with no link in
its text:

```clojure
{:type :illustration
 :content {:title \"Énoncé universel\"
           :text \"Cet énoncé n'est borné ni dans le temps … ni dans l'espace. …\"
           :inputs [{:type :definition :name \"enonce-universel\" :lang :fr :major 1}] …}}
```

*Fictional example*: a concept far more widespread in English than in French. A French
text cites it as it is, without waiting for it to exist in French:

```
… comme le montre le [[definition:nudge:en@1|nudge]] …
```

## Links

- `agora.value.lang`: the language of the cited document is part of the citation.
- `agora.entity.document`: a citation goes from one document to another.
- `agora.value.reference`: a citation is a reference, by tnlr or by id.
- `agora.value.type`: the type of the cited document is part of the citation and of the link."
  (:require
   [agora.value.identity  :as identity]
   [agora.value.reference :as reference]
   [agora.value.type      :as type]
   [agora.value.version   :as version]
   [clojure.string        :as str]))

;; ---------------------------------------------------------------------------
;; Domain
;; ---------------------------------------------------------------------------

(def schema
  "The grammar of a citation: a published version by tnlr, or a draft of the same
  publication by its `:id`."
  [:or reference/version-schema reference/tnlr-schema])

(defn by-id?
  "Does `citation` point at one version by its `:id`?"
  [citation]
  (contains? citation :id))

(def ^:private link-re #"\[\[([^\]|]+?)(?:\|([^\]]*))?\]\]")

(defn parse-target
  "The citation a link target designates (`definition:rapide:fr@1` or `id:<uuid>`), or nil."
  [target]
  (if-let [[_ id] (re-matches #"id:([0-9a-f-]{36})" target)]
    {:id (parse-uuid id)}
    (when-let [[_ t n l m] (re-matches #"([a-z-]+):([^:@]+):([a-z]{2})@(\d+)" target)]
      {:type (keyword t)
       :name n
       :lang (keyword l)
       :major (parse-long m)})))

(defn links
  "Links of `text`: `{:target :citation :label}`, `:citation` nil when the target is incomplete."
  [text]
  (for [[_ target label] (re-seq link-re (or text ""))]
    {:target target
     :citation (parse-target target)
     :label label}))

(defn render-link
  "Link text for `citation`, with an optional `label`."
  [citation label]
  (str "[["
       (if (by-id? citation)
         (str "id:" (:id citation))
         (str (name (:type citation))
              ":" (:name citation)
              ":" (name (:lang citation))
              "@" (:major citation)))
       (when label (str "|" label))
       "]]"))

(defn replace-links
  "`text` with each link's citation replaced by `(f citation)`, labels kept."
  [text f]
  (when text
    (str/replace text
                 link-re
                 (fn [[whole target label]]
                   (if-let [c (parse-target target)]
                     (render-link (f c) label)
                     whole)))))

;; ---------------------------------------------------------------------------
;; Rules
;; ---------------------------------------------------------------------------

(def rules
  "Rules on a sequence of documents."
  [{:id :citation/shape
    :description
    "a citation is an {:id}, or a tnlr following its grammar (type, name, language, major)"
    :check (fn [documents]
             (for [d documents
                   c (-> d
                         :content
                         :inputs)
                   :let [error (if (by-id? c)
                                 (when (not= #{:id} (set (keys c))) {:error {:id ["only an :id"]}})
                                 (identity/validate-tnlr c))]
                   :when error]
               {:rule :citation/shape
                :subject d
                :detail (:error error)}))}
   {:id :citation/links
    :description
    "a link is complete (type:name:language@major or id:…) and every link is a citation"
    :check (fn [documents]
             (for [d documents
                   {:keys [target citation]} (links (-> d
                                                        :content
                                                        :text))
                   :when (or (nil? citation)
                             (not (some #{citation}
                                        (-> d
                                            :content
                                            :inputs))))]
               {:rule :citation/links
                :subject d
                :detail target}))}
   {:id :citation/published-before
    :description "a citation by tnlr only cites what was already published"
    :check (fn [documents]
             (let [published (filter version/published? documents)]
               (for [d published
                     c (remove by-id?
                               (-> d
                                   :content
                                   :inputs))
                     :let [targets (filter #(= c (identity/tnlr %)) published)]
                     :when (and (seq targets)
                                (every? #(pos? (compare (-> %
                                                            :content
                                                            :published-at)
                                                        (-> d
                                                            :content
                                                            :published-at)))
                                        targets))]
                 {:rule :citation/published-before
                  :subject d
                  :detail c})))}
   {:id :citation/inputs
    :description
    "a document cites as its type says: nothing (:none), at least one document (:required), or exactly one work (:one-work)"
    :check (fn [documents]
             (for [d documents
                   :let [inputs (-> d
                                    :content
                                    :inputs)
                         expected (:inputs (type/definition-of (:type d)))]
                   :when (case expected
                           :none (seq inputs)
                           :required (empty? inputs)
                           :one-work (not (and (= 1 (count inputs))
                                               (= :work (:type (first inputs)))))
                           false)]
               {:rule :citation/inputs
                :subject d
                :detail expected}))}])
