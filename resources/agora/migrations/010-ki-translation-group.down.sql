-- Agora / Logos — translation linking (rollback)
ALTER TABLE `AGORA_KI` DROP KEY `idx_ki_translation_group`;
ALTER TABLE `AGORA_KI` DROP COLUMN `translation_group`;
