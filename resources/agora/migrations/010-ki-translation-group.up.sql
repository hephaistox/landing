-- Agora / Logos — translation linking (Wikipedia-style "other languages")
-- A concept can exist in several languages, each a fully independent KI lineage
-- (its own name/major/minor and its own graph edges — a translation is NOT a new
-- minor). `translation_group` ties those language variants together: all KIs that
-- express the same concept, in any language, share one group id. The KI page uses
-- it to offer links to the other-language versions.
--
-- All minors of one lineage share the group (it travels with edits). NULL means
-- the KI has no known translations yet.

ALTER TABLE `AGORA_KI`
  ADD COLUMN `translation_group` CHAR(36) NULL COMMENT 'Shared across a concept''s language variants' AFTER `lang`;

ALTER TABLE `AGORA_KI`
  ADD KEY `idx_ki_translation_group` (`translation_group`);
