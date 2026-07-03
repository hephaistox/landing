-- Agora / Logos — Slice 8, #36
-- Visit counter per KI lineage (name, major) — the permanent public identity.
-- Incremented each time a public KI page (/ki/{name}/{major}) is viewed. The
-- discoverability page uses it to weight a random ordering toward popular KIs.

CREATE TABLE IF NOT EXISTS `AGORA_KI_VISIT` (
  `name`   VARCHAR(255)   NOT NULL COMMENT 'KI name (public identity)',
  `major`  INT UNSIGNED   NOT NULL COMMENT 'KI major (public identity)',
  `visits` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Public-page view count',
  PRIMARY KEY (`name`, `major`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
