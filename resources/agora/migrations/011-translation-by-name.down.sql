-- Agora / Logos — rollback: identity without lang, restore translation_group.
ALTER TABLE `AGORA_KI`
  DROP INDEX `uq_ki_identity`,
  ADD UNIQUE KEY `uq_ki_identity` (`object_type`, `name`, `major`, `minor`);

ALTER TABLE `AGORA_KI`
  ADD COLUMN `translation_group` CHAR(36) NULL AFTER `lang`;
ALTER TABLE `AGORA_KI` ADD KEY `idx_ki_translation_group` (`translation_group`);
