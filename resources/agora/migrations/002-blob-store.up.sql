-- Agora / Logos — Slice 1, sub-issue #41
-- Content-addressed blob store for immutable KI text.
--
-- The spec calls for Cellar (S3) object storage. For the MVP we back it with a
-- table in the shared MySQL addon instead: same content-addressed semantics
-- (PRIMARY KEY on the SHA-256 hash gives automatic dedup), zero new infra. The
-- write-blob / read-blob functions (landing.agora.blob) are the swap point if
-- this later moves to Cellar.

CREATE TABLE IF NOT EXISTS `AGORA_BLOB` (
  `hash`    CHAR(64) NOT NULL COMMENT 'SHA-256 hex of content; the content-addressed key',
  `content` LONGTEXT NOT NULL COMMENT 'Immutable UTF-8 blob (e.g. a KI output statement)',
  PRIMARY KEY (`hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
