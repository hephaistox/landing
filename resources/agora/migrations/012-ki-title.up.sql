-- Agora / Logos — human-readable, per-language title.
--
-- `name` stays the language-neutral identity slug (URL, edges, translation
-- grouping). `title` is a free-text, per-language display headline shown as the
-- KI's heading — so a translation can carry a properly translated title while the
-- shared name still links the language siblings. Nullable: when absent the UI
-- falls back to a humanized form of the slug.

ALTER TABLE `AGORA_KI`
  ADD COLUMN `title` VARCHAR(512) NULL COMMENT 'Per-language display title' AFTER `name`;
