-- Agora / Logos — Slice 10, #38
-- Store the OAuth profile picture URL (e.g. Google's lh3.googleusercontent.com
-- avatar). NULL for password accounts, which fall back to text initials in the UI.

ALTER TABLE `AGORA_USER`
  ADD COLUMN `avatar_url` VARCHAR(1024) NULL COMMENT 'OAuth profile picture URL' AFTER `display_name`;
