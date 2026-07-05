-- Agora / Logos — translations are grouped by concept identity, not a column.
--
-- Supersedes 010's `translation_group`. A concept's translations are simply the
-- KIs that share its identity Name (T,N) in different languages — no explicit
-- link, no stored group. Creating a same-named KI in another language makes it a
-- sibling automatically, and because edges reference (name, major) with no
-- language, the whole reasoning graph is shared across languages for free.
--
-- So `lang` joins the identity key: the same (object_type, name, major, minor)
-- may now exist once per language.

ALTER TABLE `AGORA_KI` DROP KEY `idx_ki_translation_group`;
ALTER TABLE `AGORA_KI` DROP COLUMN `translation_group`;

ALTER TABLE `AGORA_KI`
  DROP INDEX `uq_ki_identity`,
  ADD UNIQUE KEY `uq_ki_identity` (`object_type`, `name`, `lang`, `major`, `minor`);
