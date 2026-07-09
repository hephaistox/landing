-- Roll back sources & external people.
-- NOTE: the AGORA_USER revert FAILS if any `external` rows or NULL emails exist — remove
-- those first (they only exist if the feature was used).
DROP TABLE IF EXISTS `AGORA_SOURCE`;

ALTER TABLE `AGORA_USER`
  MODIFY COLUMN `email` VARCHAR(320) NOT NULL,
  MODIFY COLUMN `provider` ENUM('password','google','facebook') NOT NULL DEFAULT 'password';
