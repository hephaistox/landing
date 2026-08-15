-- Agora database schema — the single authoritative snapshot of the current tables.
--
-- There is no migration runner and only one (shared Clever Cloud MySQL) database, already at
-- this schema. It is idempotent (`CREATE TABLE IF NOT EXISTS`), so it is safe to (re)run.
--
-- Bootstrapping a fresh database is TWO steps — the tables here, then the essential seed
-- **data** (this file has none):
--   1. tables:                 clojure -M scripts/agora_db.clj resources/agora/schema.sql
--   2. type-definition KIs:    from a REPL with :env-seed —
--                                (require 'landing.agora.types-seed)
--                                (landing.agora.types-seed/seed!)
--      These `type-<kind>` definition KIs are REQUIRED domain data (each kind badge links to
--      its definition; see landing.agora.document-domain/kind-def) — not optional test data.
--   (optional) a test article + KIs: clojure -M:env-dev scripts/agora_seed.clj
--
-- Only identity columns are real columns; everything else on a document is EDN in `content`
-- (immutable) / `computed` (mutable). See agora/CLAUDE.md §13.

CREATE TABLE IF NOT EXISTS `AGORA_USER` (
  `id`            CHAR(36)     NOT NULL COMMENT 'UUID',
  `provider`      ENUM('password','google','external') NOT NULL DEFAULT 'password',
  `provider_id`   VARCHAR(255) DEFAULT NULL COMMENT 'OAuth provider account id (NULL for password/external)',
  `email`         VARCHAR(320) DEFAULT NULL,
  `display_name`  VARCHAR(255) NOT NULL,
  `lang`          CHAR(2)      DEFAULT NULL COMMENT 'Preferred interface language (ISO 639-1)',
  `avatar_url`    VARCHAR(1024) DEFAULT NULL COMMENT 'OAuth profile picture URL',
  `password_hash` VARCHAR(255) DEFAULT NULL COMMENT 'bcrypt hash (NULL for OAuth/external)',
  `created_at`    DATETIME     NOT NULL COMMENT 'UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_user_email` (`email`),
  UNIQUE KEY `uq_user_provider` (`provider`, `provider_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- KIs, articles and (kind=source) citations — one polymorphic table.
CREATE TABLE IF NOT EXISTS `AGORA_DOCUMENT` (
  `id`           CHAR(36)     NOT NULL COMMENT 'UUID, stable permanent identity of a version',
  `type`         VARCHAR(32)  NOT NULL DEFAULT 'ki' COMMENT 'Object type (ki / article)',
  `name`         VARCHAR(255) NOT NULL COMMENT 'Opaque stable cid — reference/grouping key',
  `lang`         CHAR(2)      NOT NULL DEFAULT 'fr' COMMENT 'Content language (ISO 639-1)',
  `major`        INT UNSIGNED NOT NULL COMMENT 'Major version; breaking changes bump this',
  `minor`        INT UNSIGNED NULL     COMMENT 'Minor version, assigned at publish; NULL while draft (a draft is mutable, edited in place, and carries no version number until it is published)',
  `draft`        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'Unpublished draft (1) vs published (0). Drafts are excluded from resolution/discovery/edges — reachable only by exact-version URL and on the author profile — until Publish clears the flag and prunes intermediate drafts',
  `content`      LONGTEXT     NOT NULL COMMENT 'Immutable authored EDN blob',
  `computed`     LONGTEXT     NULL     COMMENT 'Mutable derived EDN blob (:pins)',
  `published_at` VARCHAR(32)  NULL     COMMENT 'Denormalized from content.:published-at (ISO-8601 UTC; sortable)',
  `publication_id` CHAR(36)   NULL     COMMENT 'The publication that created this version (permanent provenance; the publication lineage cid = AGORA_DOCUMENT.name where type=publication, stable across the publication renames). NULL for versions created outside any publication',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_ki_identity` (`type`, `name`, `lang`, `major`, `minor`),
  KEY `idx_ki_lang` (`type`, `lang`),
  KEY `idx_doc_draft` (`type`, `lang`, `draft`),
  KEY `idx_doc_publication` (`publication_id`),
  KEY `idx_doc_published` (`published_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Derived reverse-edge cache (input TNLR → successor id); rebuilt from documents.
CREATE TABLE IF NOT EXISTS `AGORA_SUCCESSOR` (
  `input_type`   VARCHAR(32)  NOT NULL DEFAULT 'ki',
  `input_name`   VARCHAR(255) NOT NULL,
  `input_lang`   CHAR(2)      NOT NULL,
  `input_major`  INT UNSIGNED NOT NULL,
  `successor_id` CHAR(36)     NOT NULL,
  PRIMARY KEY (`input_type`, `input_name`, `input_lang`, `input_major`, `successor_id`),
  KEY `idx_by_input` (`input_type`, `input_name`, `input_lang`, `input_major`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The shared bibliographic *work* a kind=source KI references.
CREATE TABLE IF NOT EXISTS `AGORA_SOURCE` (
  `source_id`   CHAR(36)      NOT NULL COMMENT 'UUID',
  `author_id`   CHAR(36)      NOT NULL COMMENT 'Author — AGORA_USER.id (may be external)',
  `author_name` VARCHAR(255)  NOT NULL COMMENT 'Author display name, denormalized at creation',
  `title`       VARCHAR(512)  NOT NULL,
  `year`        SMALLINT      NULL,
  `editor`      VARCHAR(255)  NULL,
  `url`         VARCHAR(1000) NULL,
  `owner_id`    CHAR(36)      NOT NULL COMMENT 'Owner who created the record — AGORA_USER.id',
  `created_at`  DATETIME      NOT NULL COMMENT 'UTC',
  PRIMARY KEY (`source_id`),
  KEY `idx_source_title`  (`title`),
  KEY `idx_source_author` (`author_id`),
  KEY `idx_source_recent` (`owner_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
