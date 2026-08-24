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
  `display_name`  VARCHAR(255) NOT NULL COMMENT 'Public display name, generated at creation — never a civil name',
  `display_name_key` VARCHAR(255) DEFAULT NULL COMMENT 'Normalized display_name, which the uniqueness below is held on. NULL for provider=external: the login-less people the corpus cites are not accounts and may be homonyms, so uniqueness applies to accounts only — that restriction, not the normalization, is why this is a column rather than a unique index on display_name. Uniqueness of the displayed name is what keeps the rename endpoint from being an impersonation tool: the id is never shown, the name is all a reader sees',
  `lang`          CHAR(2)      DEFAULT NULL COMMENT 'Preferred interface language (ISO 639-1)',
  `avatar_url`    VARCHAR(1024) DEFAULT NULL COMMENT 'OAuth profile picture URL',
  `password_hash` VARCHAR(255) DEFAULT NULL COMMENT 'bcrypt hash (NULL for OAuth/external)',
  `created_at`    DATETIME     NOT NULL COMMENT 'UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_user_email` (`email`),
  UNIQUE KEY `uq_user_display_name` (`display_name_key`),
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
  `owner_id`     CHAR(36)     NOT NULL COMMENT 'The person accountable for this version. A column, not a field of the immutable content: attribution has a lifecycle the claim does not — it is rectified on a rename, reassigned on an erasure or a transfer. The foreign key is what makes "a person with documents cannot be deleted" a rule of the database rather than a convention of the code',
  `author_id`    CHAR(36)     NULL     COMMENT 'The person a work is CREDITED to (Sun Tzu), when that is not its owner (the contributor maintaining the record). NULL on every other document, where the byline is the owner',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_ki_identity` (`type`, `name`, `lang`, `major`, `minor`),
  KEY `idx_ki_lang` (`type`, `lang`),
  KEY `idx_doc_draft` (`type`, `lang`, `draft`),
  KEY `idx_doc_publication` (`publication_id`),
  KEY `idx_doc_published` (`published_at`),
  KEY `idx_doc_owner` (`owner_id`),
  KEY `idx_doc_author` (`author_id`),
  CONSTRAINT `fk_doc_owner` FOREIGN KEY (`owner_id`) REFERENCES `AGORA_USER` (`id`),
  CONSTRAINT `fk_doc_author` FOREIGN KEY (`author_id`) REFERENCES `AGORA_USER` (`id`)
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

-- The one person every erased account is absorbed into. Seeded with the schema because the
-- erasure cannot run without it, and because the foreign key above needs it to exist before any
-- document can point at it.
--
-- One shared row, not one per erased account: an id that gathers exactly one person's documents is
-- personal data however anonymous the name on it reads, and we published that id for as long as the
-- account lived. Erasure reattributes to this row and deletes the account, so what survives groups
-- every erased person together and identifies none.
--
-- The id is fixed and obviously synthetic so it is recognizable in any dump, log or API response.
-- The platform itself, as a person: what the seeded corpus belongs to — the epistemic type
-- definitions, and the FAQ next. Not anonymous, and the distinction is the point: Agora is an
-- identified author, accountable for what it signed, where the row above is the absence of one.
--
-- Seeded at a fixed id rather than minted by whichever seed runs first, so every environment agrees
-- on who owns the platform's own content.
--
-- Neither of these two may be erased (`auth/reserved-ids`).
INSERT IGNORE INTO `AGORA_USER` (`id`, `provider`, `display_name`, `display_name_key`, `created_at`)
VALUES ('00000000-0000-0000-0000-000000000000', 'external', 'Anonymus', NULL, UTC_TIMESTAMP()),
       ('00000000-0000-0000-0000-000000000001', 'external', 'Agora',    NULL, UTC_TIMESTAMP());
