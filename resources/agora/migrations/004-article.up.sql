-- Agora / Logos — Slice 3-bis, #31
-- Article identity table.
--
-- Like a KI, an article stores only the hash of its body; the body text lives in
-- the content-addressed blob store (AGORA_BLOB, #41), resolved via read-blob.
-- Articles are the primary SEO surface later; at this stage one is seeded
-- manually and shown on a hidden route.

CREATE TABLE IF NOT EXISTS `AGORA_ARTICLE` (
  `id`           CHAR(36)     NOT NULL COMMENT 'UUID, stable permanent identity',
  `title`        VARCHAR(500) NOT NULL COMMENT 'Article title',
  `body_hash`    CHAR(64)     NOT NULL COMMENT 'SHA-256 hex of the body blob (Cellar/AGORA_BLOB)',
  `published_at` DATETIME     NOT NULL COMMENT 'Immutable first-publication timestamp (UTC)',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
