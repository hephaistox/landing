-- Agora / Logos — Slice 1, sub-issue #40
-- Knowledge Item (KI) identity table.
--
-- Identity model: a KI is uniquely identified by (type, name, major, minor).
-- The output statement text itself is NOT stored here: only the SHA-256 hash of
-- its content-addressed blob (the blob will live in Cellar, see #41). The DB holds
-- graph structure and references only.

CREATE TABLE IF NOT EXISTS `AGORA_KI` (
  `id`                    CHAR(36)     NOT NULL COMMENT 'UUID, stable permanent identity',
  `name`                  VARCHAR(255) NOT NULL COMMENT 'Human-readable identity slug, part of the URL',
  `type`                  ENUM('derived',
                               'verifiable-claim',
                               'postulate',
                               'stance',
                               'belief',
                               'credo')   NOT NULL COMMENT 'KI type; drives lifecycle and challenge mechanism',
  `major`                 INT UNSIGNED NOT NULL COMMENT 'Major version; breaking changes bump this',
  `minor`                 INT UNSIGNED NOT NULL COMMENT 'Minor version; clarifications bump this',
  `output_statement_hash` CHAR(64)     NOT NULL COMMENT 'SHA-256 hex of the output statement blob (Cellar)',
  `owner_id`              CHAR(36)     NULL     COMMENT 'Owning user; no user table yet (#10), nullable for now',
  `published_at`          DATETIME     NOT NULL COMMENT 'Immutable first-publication timestamp (proof of antecedence)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_ki_identity` (`type`, `name`, `major`, `minor`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
