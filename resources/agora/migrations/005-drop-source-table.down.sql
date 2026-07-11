-- Recreate AGORA_SOURCE (rollback of 005). Data is not restored — sources authored as
-- documents remain as `type='source'` rows; this only re-adds the empty table.

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
