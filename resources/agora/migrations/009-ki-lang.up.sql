-- Agora / Logos — content internationalisation
-- Tag each KI with the language of its content (ISO 639-1, the first two letters
-- of a locale: 'fr', 'en', extensible). Matches the landing site's fr/en scheme.
-- The discover page filters by this; KI pages show it as a badge. Immutable per
-- version: a translation is a separate KI, not a new minor of the same one.

ALTER TABLE `AGORA_KI`
  ADD COLUMN `lang` CHAR(2) NOT NULL DEFAULT 'fr' COMMENT 'Content language (ISO 639-1)' AFTER `type`;

ALTER TABLE `AGORA_KI`
  ADD KEY `idx_ki_lang` (`object_type`, `lang`);
