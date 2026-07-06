(ns agora-seed
  "Fresh-DB test seed, built through the domain/adapter — no hand-written EDN. Run
  after applying the schema migration:
    clojure -M scripts/agora_db.clj resources/agora/migrations/001-schema.up.sql
    clojure -M:env-dev scripts/agora_seed.clj

  Uses a real `ns`/`:require` (not runtime `alias`) so it is linted with `src` — a
  signature change in landing.agora.ki fails `bb lint` here."
  (:require
   [landing.agora.db :as db]
   [landing.agora.ki :as ki]
   [next.jdbc        :as jdbc]))

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

;; One article (body inline; no ki helper for articles).
(jdbc/execute!
 db/ds
 ["INSERT INTO AGORA_ARTICLE (id, title, body, published_at) VALUES (?, ?, ?, UTC_TIMESTAMP())"
  "00000000-0000-0000-0000-0000000000a1"
  "Reasoning, made legible"
  "Most knowledge tools store conclusions. This platform stores reasoning chains, one implication at a time.

The first knowledge item in this graph is a postulate: our collective confidence in any claim is partial and always evolving. From it follows a derived conclusion, that a knowledge platform should record graded confidence rather than forcing binary true or false verdicts.

This article is a placeholder seeded directly in the database."])

(println :seeded)
(System/exit 0)
