(ns agora-seed
  "Fresh-DB test seed, built through the domain/adapter — no hand-written EDN. Run
  after applying the schema migration:
    clojure -M scripts/agora_db.clj resources/agora/migrations/001-schema.up.sql
    clojure -M:env-dev scripts/agora_seed.clj

  Uses a real `ns`/`:require` (not runtime `alias`) so it is linted with `src` — a
  signature change in landing.agora.ki / landing.agora.article fails `bb lint` here."
  (:require
   [landing.agora.article :as article]
   [landing.agora.ki      :as ki]))

;; A postulate (no inputs) and an inference that pins it, plus a clarified minor.
(ki/create-ki
 nil
 {:name "confidence-is-partial"
  :kind "postulate"
  :lang "fr"
  :output-statement
  "Human collective confidence in any claim is always partial and evolving; knowledge therefore lives in a fuzzy, probabilistic space rather than in binary truth."})
(ki/create-ki
 nil
 {:name "confidence-over-binary"
  :kind "inference"
  :lang "fr"
  :output-statement
  "Because our collective confidence in any claim is partial and evolving, a knowledge platform should record and expose degrees of confidence rather than forcing binary true/false conclusions."})
(ki/add-input (:id (ki/fetch-ki-by-major "confidence-over-binary" 1 "fr"))
              nil
              {:name "confidence-is-partial"
               :major 1})
(ki/edit-ki
 (:id (ki/fetch-ki-by-major "confidence-over-binary" 1 "fr"))
 nil
 {:kind "inference"
  :output-statement
  "Because our collective confidence in any claim is partial and always evolving, a knowledge platform should record and expose graded confidence rather than forcing binary true/false conclusions."})

;; One article: a versioned AGORA_DOCUMENT (type "article") citing two KIs via living
;; [[ki:name@major]] tokens in its body (parsed into content.:inputs and pinned).
(article/create-article
 nil
 {:name "reasoning-made-legible"
  :lang "en"
  :title "Reasoning, made legible"
  :body
  "Most knowledge tools store conclusions. This platform stores reasoning chains, one implication at a time.

The first knowledge item in this graph is a postulate — [[ki:confidence-is-partial@1|our collective confidence is always partial]]. From it follows [[ki:confidence-over-binary@1]]: a knowledge platform should record graded confidence rather than forcing binary true or false verdicts.

This article is a placeholder seeded directly in the database. Hover a highlighted term to see the Knowledge Item behind it."})

;; A French sibling (same name → grouped as a translation), so the default (fr)
;; discover page is non-empty too.
(article/create-article
 nil
 {:name "reasoning-made-legible"
  :lang "fr"
  :title "Le raisonnement, rendu lisible"
  :body
  "La plupart des outils de connaissance stockent des conclusions. Cette plateforme stocke des chaînes de raisonnement, une implication à la fois.

Le premier élément de connaissance de ce graphe est un postulat — [[ki:confidence-is-partial@1|notre confiance collective est toujours partielle]]. Il s'ensuit [[ki:confidence-over-binary@1]] : une plateforme de connaissance devrait consigner une confiance graduée plutôt que d'imposer des verdicts binaires, vrai ou faux.

Cet article est un exemple inséré directement dans la base. Survolez un terme mis en évidence pour voir l'élément de connaissance correspondant."})

(println :seeded)
(System/exit 0)
