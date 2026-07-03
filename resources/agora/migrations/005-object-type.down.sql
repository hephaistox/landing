-- Agora / Logos — object type vs KI type (#33 follow-up, rollback)

ALTER TABLE `AGORA_KI_EDGE`
  DROP INDEX `uq_edge`,
  DROP INDEX `idx_edge_by_output`,
  DROP INDEX `idx_edge_by_input`,
  ADD COLUMN `input_type`  ENUM('derived','verifiable-claim','postulate','stance','belief','credo')
                           NOT NULL DEFAULT 'derived' AFTER `id`,
  ADD COLUMN `output_type` ENUM('derived','verifiable-claim','postulate','stance','belief','credo')
                           NOT NULL DEFAULT 'derived' AFTER `input_major`,
  ADD UNIQUE KEY `uq_edge` (`input_type`, `input_name`, `input_major`,
                           `output_type`, `output_name`, `output_major`),
  ADD KEY `idx_edge_by_output` (`output_type`, `output_name`, `output_major`),
  ADD KEY `idx_edge_by_input` (`input_type`, `input_name`, `input_major`);

ALTER TABLE `AGORA_KI`
  DROP INDEX `uq_ki_identity`,
  ADD UNIQUE KEY `uq_ki_identity` (`type`, `name`, `major`, `minor`);

ALTER TABLE `AGORA_KI` DROP COLUMN `object_type`;
