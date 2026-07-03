-- Agora / Logos — object type vs KI type (#33 follow-up)
--
-- The identity's T is the OBJECT TYPE (ki now; objection later — the PLM "change"
-- analog), NOT the KI's epistemic classification. So:
--  - AGORA_KI gains `object_type` and keys identity on (object_type, name, major, minor).
--  - The epistemic `type` (derived/postulate/…) becomes a plain MUTABLE attribute:
--    reclassifying is an edit (new minor), not a new object, and edges follow.
--  - Edges reference (name, major) within the KI object type — the epistemic type
--    is dropped from the edge key.
--
-- Apply order: run after migrations 001–004; seeds run after this.

ALTER TABLE `AGORA_KI`
  ADD COLUMN `object_type` ENUM('ki', 'objection') NOT NULL DEFAULT 'ki' AFTER `id`;

ALTER TABLE `AGORA_KI`
  DROP INDEX `uq_ki_identity`,
  ADD UNIQUE KEY `uq_ki_identity` (`object_type`, `name`, `major`, `minor`);

ALTER TABLE `AGORA_KI_EDGE`
  DROP INDEX `uq_edge`,
  DROP INDEX `idx_edge_by_output`,
  DROP INDEX `idx_edge_by_input`,
  DROP COLUMN `input_type`,
  DROP COLUMN `output_type`,
  ADD UNIQUE KEY `uq_edge` (`input_name`, `input_major`, `output_name`, `output_major`),
  ADD KEY `idx_edge_by_output` (`output_name`, `output_major`),
  ADD KEY `idx_edge_by_input` (`input_name`, `input_major`);
