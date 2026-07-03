-- Agora / Logos — Slice 2, #29
-- A second KI (a `derived` conclusion) linked to the seeded postulate (#40),
-- so the graph has one edge: confidence-is-partial  →  confidence-over-binary.

-- 1. Blob for KI2's output statement (hash = SHA-256 of the exact text).
INSERT INTO `AGORA_BLOB` (`hash`, `content`) VALUES
  ('0bbf913e2ece128f717effdfe56e61e724e55ecfcdd0b8fa55de48c801bd62a0',
   'Because our collective confidence in any claim is partial and evolving, a knowledge platform should record and expose degrees of confidence rather than forcing binary true/false conclusions.');

-- 2. KI2 — a derived KI.
INSERT INTO `AGORA_KI`
  (`id`, `name`, `type`, `major`, `minor`, `output_statement_hash`, `owner_id`, `published_at`)
VALUES
  ('00000000-0000-0000-0000-000000000002',
   'confidence-over-binary',
   'derived',
   1,
   0,
   '0bbf913e2ece128f717effdfe56e61e724e55ecfcdd0b8fa55de48c801bd62a0',
   NULL,
   '2026-07-02 00:00:00');

-- 3. Edge: postulate confidence-is-partial (input) implies derived confidence-over-binary (output).
INSERT INTO `AGORA_KI_EDGE`
  (`id`, `input_type`, `input_name`, `input_major`, `output_type`, `output_name`, `output_major`)
VALUES
  ('00000000-0000-0000-0000-0000000000e1',
   'postulate', 'confidence-is-partial',  1,
   'derived',   'confidence-over-binary', 1);
