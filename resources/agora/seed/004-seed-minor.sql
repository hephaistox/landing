-- Agora / Logos — Slice 3, #30
-- A second minor (v1.1) of confidence-over-binary: a clarification of the v1.0
-- statement (same type/name/major, minor bumped). No edge changes — the edge
-- KI1 -> confidence-over-binary references Major 1 only, so resolve-major now
-- auto-resolves it to this newer minor. This demonstrates versioning in links.

-- Blob for the clarified statement.
INSERT INTO `AGORA_BLOB` (`hash`, `content`) VALUES
  ('45ef55ddc399d04ab6ef41a8f3874f5d91e26e675e7d24b596d0c01789282860',
   'Because our collective confidence in any claim is partial and always evolving, a knowledge platform should record and expose graded confidence rather than forcing binary true/false conclusions.');

-- v1.1 row (minor = 1). The v1.0 row (minor = 0) stays, immutable.
INSERT INTO `AGORA_KI`
  (`id`, `name`, `type`, `major`, `minor`, `output_statement_hash`, `owner_id`, `published_at`)
VALUES
  ('00000000-0000-0000-0000-000000000012',
   'confidence-over-binary',
   'derived',
   1,
   1,
   '45ef55ddc399d04ab6ef41a8f3874f5d91e26e675e7d24b596d0c01789282860',
   NULL,
   '2026-07-03 00:00:00');
