-- Agora / Logos — interface language preference on the account.
--
-- The user's preferred interface language (chrome, discover feed, search). Cached
-- client-side in localStorage for speed and loaded from here at login so it
-- follows the account across devices. NULL = never chosen (fall back to the
-- browser/cookie default). Distinct from a KI's content language, which lives in
-- the permalink URL.

ALTER TABLE `AGORA_USER`
  ADD COLUMN `lang` CHAR(2) NULL COMMENT 'Preferred interface language (ISO 639-1)' AFTER `display_name`;
