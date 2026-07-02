-- Agora / Logos — Slice 1, sub-issue #41
-- Blob for the KI seeded in #40. Its output_statement_hash points here; storing
-- the matching content makes read-blob resolve the KI's statement text.
-- The hash is SHA-256 of the exact content string below (no trailing newline).

INSERT INTO `AGORA_BLOB` (`hash`, `content`) VALUES
  ('4f7ba6465790bc68e1b08e0555ad773360f86e1fa8271c5b99f8f0185572cef3',
   'Human collective confidence in any claim is always partial and evolving; knowledge therefore lives in a fuzzy, probabilistic space rather than in binary truth.');
