-- Agora / Logos — Slice 10, #38
-- Minimal user account. Supports both email/password accounts and (later) OAuth:
--  - password accounts: provider='password', password_hash set, provider_id NULL
--  - OAuth accounts:     provider='google'|'facebook', provider_id set, no hash
-- A user is referenced as a KI owner (AGORA_KI.owner_id).

CREATE TABLE IF NOT EXISTS `AGORA_USER` (
  `id`            CHAR(36)     NOT NULL COMMENT 'UUID',
  `provider`      ENUM('password', 'google', 'facebook') NOT NULL DEFAULT 'password',
  `provider_id`   VARCHAR(255) NULL     COMMENT 'OAuth provider account id (NULL for password)',
  `email`         VARCHAR(320) NOT NULL,
  `display_name`  VARCHAR(255) NOT NULL,
  `password_hash` VARCHAR(255) NULL     COMMENT 'bcrypt hash (NULL for OAuth accounts)',
  `created_at`    DATETIME     NOT NULL COMMENT 'UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_user_email` (`email`),
  UNIQUE KEY `uq_user_provider` (`provider`, `provider_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
