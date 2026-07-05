-- Agora — MVP schema (consolidated).
--
-- Content-addressed store for immutable KI/article text. Addressed by the SHA-256
-- hex of the content (PRIMARY KEY gives automatic dedup). MySQL for the MVP;
-- Cellar/S3 is the intended swap-point (see landing.agora.blob).
CREATE TABLE IF NOT EXISTS `AGORA_BLOB` (
  `hash`    CHAR(64) NOT NULL COMMENT 'SHA-256 hex of content; the content-addressed key',
  `content` LONGTEXT NOT NULL COMMENT 'Immutable UTF-8 blob (e.g. a KI output statement)',
  PRIMARY KEY (`hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Registered contributors. Read is anonymous; authoring requires an account.
CREATE TABLE IF NOT EXISTS `AGORA_USER` (
  `id`            CHAR(36)     NOT NULL COMMENT 'UUID',
  `provider`      ENUM('password', 'google', 'facebook') NOT NULL DEFAULT 'password',
  `provider_id`   VARCHAR(255) NULL     COMMENT 'OAuth provider account id (NULL for password accounts)',
  `email`         VARCHAR(320) NOT NULL,
  `display_name`  VARCHAR(255) NOT NULL COMMENT 'Alias',
  `lang`          CHAR(2)      NULL     COMMENT 'Preferred interface language (ISO 639-1)',
  `avatar_url`    VARCHAR(1024) NULL    COMMENT 'OAuth profile picture URL',
  `password_hash` VARCHAR(255) NULL     COMMENT 'bcrypt hash (NULL for OAuth accounts)',
  `created_at`    DATETIME     NOT NULL COMMENT 'UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_user_email` (`email`),
  UNIQUE KEY `uq_user_provider` (`provider`, `provider_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Knowledge Item (and, from Layer 2, Objection) — the immutable, versioned node.
-- Identity = (object_type, name, lang, major, minor). `lang` is the content
-- language: each language is its own lineage, tied to its siblings only by sharing
-- `name`. `type` (the epistemic classification) and `title` are plain mutable
-- attributes, not identity. Only the statement's hash is stored here.
CREATE TABLE IF NOT EXISTS `AGORA_NODE` (
  `id`                    CHAR(36)     NOT NULL COMMENT 'UUID, stable permanent identity',
  `object_type`           ENUM('ki', 'objection') NOT NULL DEFAULT 'ki',
  `name`                  VARCHAR(255) NOT NULL COMMENT 'Language-neutral identity slug (URL, edges, translation grouping)',
  `title`                 VARCHAR(512) NULL     COMMENT 'Per-language display title (falls back to a humanized name)',
  `type`                  ENUM('derived',
                               'verifiable-claim',
                               'postulate',
                               'stance',
                               'belief',
                               'credo') NOT NULL COMMENT 'Epistemic type; a mutable label (no per-type behaviour yet)',
  `lang`                  CHAR(2)      NOT NULL DEFAULT 'fr' COMMENT 'Content language (ISO 639-1)',
  `major`                 INT UNSIGNED NOT NULL COMMENT 'Major version; breaking changes bump this',
  `minor`                 INT UNSIGNED NOT NULL COMMENT 'Minor version; clarifications bump this',
  `output_statement_hash` CHAR(64)     NOT NULL COMMENT 'SHA-256 hex of the output statement blob (AGORA_BLOB)',
  `owner_id`              CHAR(36)     NULL     COMMENT 'Owning user (AGORA_USER.id)',
  `published_at`          DATETIME     NOT NULL COMMENT 'Immutable first-publication timestamp, UTC (proof of antecedence)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_ki_identity` (`object_type`, `name`, `lang`, `major`, `minor`),
  KEY `idx_ki_lang` (`object_type`, `lang`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Reasoning edges: input KI implies output KI. Language-neutral — references
-- (name, major) only, so one edge set serves every language of a concept.
CREATE TABLE IF NOT EXISTS `AGORA_NODE_EDGE` (
  `id`           CHAR(36)     NOT NULL COMMENT 'UUID, edge identity',
  `input_name`   VARCHAR(255) NOT NULL COMMENT 'Input KI name',
  `input_major`  INT UNSIGNED NOT NULL COMMENT 'Input KI major version',
  `output_name`  VARCHAR(255) NOT NULL COMMENT 'Output KI name',
  `output_major` INT UNSIGNED NOT NULL COMMENT 'Output KI major version',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_edge` (`input_name`, `input_major`, `output_name`, `output_major`),
  KEY `idx_edge_by_output` (`output_name`, `output_major`),
  KEY `idx_edge_by_input` (`input_name`, `input_major`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Live computed state per node lineage (name, major) — values derived from the
-- graph rather than authored, kept out of the immutable node rows. Visits (driving
-- discoverability weighting) today; confidence and other computed signals later.
CREATE TABLE IF NOT EXISTS `AGORA_NODE_DYNAMIC` (
  `name`   VARCHAR(255)   NOT NULL COMMENT 'Node name (public identity)',
  `major`  INT UNSIGNED   NOT NULL COMMENT 'Node major (public identity)',
  `visits` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Public-page view count',
  PRIMARY KEY (`name`, `major`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Articles (body in AGORA_BLOB). Authoring UI is Layer 2; seeded manually for now.
CREATE TABLE IF NOT EXISTS `AGORA_ARTICLE` (
  `id`           CHAR(36)     NOT NULL COMMENT 'UUID, stable permanent identity',
  `title`        VARCHAR(500) NOT NULL COMMENT 'Article title',
  `body_hash`    CHAR(64)     NOT NULL COMMENT 'SHA-256 hex of the body blob (AGORA_BLOB)',
  `published_at` DATETIME     NOT NULL COMMENT 'Immutable first-publication timestamp (UTC)',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
