-- Agora — test seed (consolidated).
--
-- Throwaway data to make the app non-empty in dev: two linked KIs (a postulate
-- and a derived conclusion, one with a second minor), plus one article. Text is
-- content-addressed in AGORA_BLOB (hash = SHA-256 of the exact content). Applied
-- by scripts/agora_db.clj after the schema migration.

-- Content blobs (statements + article body).
INSERT INTO `AGORA_BLOB` (`hash`, `content`) VALUES
  ('4f7ba6465790bc68e1b08e0555ad773360f86e1fa8271c5b99f8f0185572cef3',
   'Human collective confidence in any claim is always partial and evolving; knowledge therefore lives in a fuzzy, probabilistic space rather than in binary truth.'),
  ('0bbf913e2ece128f717effdfe56e61e724e55ecfcdd0b8fa55de48c801bd62a0',
   'Because our collective confidence in any claim is partial and evolving, a knowledge platform should record and expose degrees of confidence rather than forcing binary true/false conclusions.'),
  ('45ef55ddc399d04ab6ef41a8f3874f5d91e26e675e7d24b596d0c01789282860',
   'Because our collective confidence in any claim is partial and always evolving, a knowledge platform should record and expose graded confidence rather than forcing binary true/false conclusions.'),
  ('7c16072356a54dcffd9d7b1971c1b51d6162bca464ea2fbd0a5ec3c89e87efed',
   'Most knowledge tools store conclusions. This platform stores reasoning chains, one implication at a time.

The first knowledge item in this graph is a postulate: our collective confidence in any claim is partial and always evolving. From it follows a derived conclusion, that a knowledge platform should record graded confidence rather than forcing binary true or false verdicts.

This article is a placeholder seeded directly in the database. Later slices will let authors write articles whose terms and arguments link to the knowledge items that support them.');

-- KIs. confidence-is-partial (postulate, no inputs) implies confidence-over-binary
-- (derived), which has a second minor (a clarified statement).
INSERT INTO `AGORA_NODE`
  (`id`, `name`, `type`, `major`, `minor`, `output_statement_hash`, `owner_id`, `published_at`)
VALUES
  ('00000000-0000-0000-0000-000000000001',
   'confidence-is-partial',  'postulate', 1, 0,
   '4f7ba6465790bc68e1b08e0555ad773360f86e1fa8271c5b99f8f0185572cef3', NULL, '2026-07-02 00:00:00'),
  ('00000000-0000-0000-0000-000000000002',
   'confidence-over-binary', 'derived',   1, 0,
   '0bbf913e2ece128f717effdfe56e61e724e55ecfcdd0b8fa55de48c801bd62a0', NULL, '2026-07-02 00:00:00'),
  ('00000000-0000-0000-0000-000000000012',
   'confidence-over-binary', 'derived',   1, 1,
   '45ef55ddc399d04ab6ef41a8f3874f5d91e26e675e7d24b596d0c01789282860', NULL, '2026-07-03 00:00:00');

-- Edge: confidence-is-partial → confidence-over-binary. Each endpoint is a node
-- identity at (object_type, name, major) — both KIs here.
INSERT INTO `AGORA_NODE_EDGE`
  (`id`, `input_object_type`, `input_name`, `input_major`, `output_object_type`, `output_name`, `output_major`)
VALUES
  ('00000000-0000-0000-0000-0000000000e1',
   'ki', 'confidence-is-partial', 1, 'ki', 'confidence-over-binary', 1);

-- One article, body content-addressed above.
INSERT INTO `AGORA_ARTICLE` (`id`, `title`, `body_hash`, `published_at`) VALUES
  ('00000000-0000-0000-0000-0000000000a1',
   'Reasoning, made legible',
   '7c16072356a54dcffd9d7b1971c1b51d6162bca464ea2fbd0a5ec3c89e87efed',
   '2026-07-03 00:00:00');
