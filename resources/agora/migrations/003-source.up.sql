-- Sources & external people.
--
-- 1. Let AGORA_USER hold external, login-less cited *people* (e.g. "Sun Tzu"): a new
--    `external` provider value, and a nullable `email` (external people have none).
--    `uq_user_email` survives — MySQL UNIQUE allows many NULLs, real emails stay unique.
-- 2. AGORA_SOURCE — a reusable bibliographic *work*, authored by a person (AGORA_USER),
--    reused across many documents; a document cites it via {source-id, locator}.
--
-- Apply manually (no runner):
--   clojure -M scripts/agora_db.clj resources/agora/migrations/003-source.up.sql

ALTER TABLE `AGORA_USER`
  MODIFY COLUMN `provider` ENUM('password','google','facebook','external')
    NOT NULL DEFAULT 'password',
  MODIFY COLUMN `email` VARCHAR(320) NULL;

CREATE TABLE IF NOT EXISTS `AGORA_SOURCE` (
  `id`         CHAR(36)     NOT NULL COMMENT 'UUID',
  `person_id`  CHAR(36)     NOT NULL COMMENT 'Author — AGORA_USER.id (may be external)',
  `title`      VARCHAR(512) NOT NULL COMMENT 'Work title',
  `year`       SMALLINT     NULL     COMMENT 'Publication year (nullable — unknown/ancient)',
  `editor`     VARCHAR(255) NULL     COMMENT 'Publisher/editor (free text)',
  `created_by` CHAR(36)     NOT NULL COMMENT 'Owner who created the record — AGORA_USER.id',
  `created_at` DATETIME     NOT NULL COMMENT 'UTC',
  PRIMARY KEY (`id`),
  KEY `idx_source_title`  (`title`),
  KEY `idx_source_person` (`person_id`),
  KEY `idx_source_recent` (`created_by`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
