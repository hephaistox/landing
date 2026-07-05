-- Agora / Logos — content internationalisation (rollback)
ALTER TABLE `AGORA_KI` DROP KEY `idx_ki_lang`;
ALTER TABLE `AGORA_KI` DROP COLUMN `lang`;
