-- Agora / Logos — Slice 1, sub-issue #40
-- Seed one KI manually so the full vertical slice (#1a..#1h) has something to display.
--
-- This is a `postulate` (declared foundation): no inputs, which fits Slice 1 where
-- no edges exist yet. It is the graph's first primitive.
--
-- output_statement_hash below is the SHA-256 of the exact statement text:
--   "Human collective confidence in any claim is always partial and evolving;
--    knowledge therefore lives in a fuzzy, probabilistic space rather than in
--    binary truth."
-- The matching Cellar blob will be written under this same hash in #41. Until then
-- the row exists; the text is resolved from Cellar once the blob store is wired up.
--
-- The fixed id lets the hidden route (#1h) and API (#1e) reference it deterministically.

INSERT INTO `AGORA_KI`
  (`id`, `name`, `type`, `major`, `minor`, `output_statement_hash`, `owner_id`, `published_at`)
VALUES
  ('00000000-0000-0000-0000-000000000001',
   'confidence-is-partial',
   'postulate',
   1,
   0,
   '4f7ba6465790bc68e1b08e0555ad773360f86e1fa8271c5b99f8f0185572cef3',
   NULL,
   '2026-07-02 00:00:00');
