(ns agora.value.lang
  "Language.

## Definition

A **language** is the language a text is written in, noted by its two-letter ISO 639-1
code: `:fr`, `:en`.

## Goals

- Read agora in one's language.
- Write the same thought in several languages, without making it several thoughts.

## Examples

The title \"Ma voiture est rapide\" is in `:fr`. The title \"My car is fast\" is in `:en`.

## Links

None.")

;; ---------------------------------------------------------------------------
;; Domain
;; ---------------------------------------------------------------------------

(def langs "Languages, as ISO 639-1 keywords." #{:fr :en})

(def schema "The grammar of a language." (into [:enum] (sort langs)))

;; ---------------------------------------------------------------------------
;; Rules
;; ---------------------------------------------------------------------------

(def rules
  "Rules on a sequence of documents."
  [{:id :lang/known
    :description "a document and every citation by tnlr are in a known language"
    :check (fn [documents]
             (for [d documents
                   x (cons d
                           (remove :id
                                   (-> d
                                       :content
                                       :inputs)))
                   :when (not (langs (:lang x)))]
               {:rule :lang/known
                :subject d
                :detail (:lang x)}))}])
