-- Agora — MVP schema (consolidated).
--
-- All text (KI statements, article bodies) is stored inline on its own row; there
-- is no separate blob store and no derived counter table — a node's mutable state
-- lives in its EDN `envelope`.

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
-- Identity is the only thing kept as columns (so keys/indexes never look into EDN):
-- id + TNLR (type, name, lang, major) + minor. Everything else is two EDN blobs:
--   content  (immutable): {:kind :title :statement :author :owner-id :published-at}
--   computed (mutable):   {:inputs [{:tnlr {:type :name :lang :major} :id}]}
-- `type` is the object type (the T; `ki` / `objection`) — a plain string, NOT enforced
-- by the DB (the canonical set lives in landing.agora.document-domain).
CREATE TABLE IF NOT EXISTS `AGORA_NODE` (
  `id`       CHAR(36)     NOT NULL COMMENT 'UUID, permanent identity of this exact version',
  `type`     VARCHAR(32)  NOT NULL DEFAULT 'ki' COMMENT 'Object type — identity T (ki/objection)',
  `name`     VARCHAR(255) NOT NULL COMMENT 'Identity N (language-neutral slug)',
  `lang`     CHAR(2)      NOT NULL DEFAULT 'fr' COMMENT 'Identity L (content language)',
  `major`    INT UNSIGNED NOT NULL COMMENT 'Identity R (major version)',
  `minor`    INT UNSIGNED NOT NULL COMMENT 'Minor version (the latest is the current one)',
  `content`  LONGTEXT     NOT NULL COMMENT 'Immutable content EDN',
  `computed` LONGTEXT     NULL     COMMENT 'Mutable computed EDN (pinned inputs, …); rebuildable',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_ki_identity` (`type`, `name`, `lang`, `major`, `minor`),
  KEY `idx_latest` (`type`, `name`, `lang`, `major`, `minor`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Reverse-edge CACHE. A node embeds its own inputs (pinned) in `computed`; the opposite
-- direction — "which KIs use this concept as an input?" — is precomputed here so a read
-- never traverses. Keyed by the input's TNLR (type, name, lang, major) → the id of each
-- KI that pins it. Derived, so safe to drop and rebuilt daily (and incrementally on write).
CREATE TABLE IF NOT EXISTS `AGORA_SUCCESSOR` (
  `input_type`   VARCHAR(32)  NOT NULL DEFAULT 'ki' COMMENT 'Input TNLR — T',
  `input_name`   VARCHAR(255) NOT NULL COMMENT 'Input TNLR — N',
  `input_lang`   CHAR(2)      NOT NULL COMMENT 'Input TNLR — L',
  `input_major`  INT UNSIGNED NOT NULL COMMENT 'Input TNLR — R',
  `successor_id` CHAR(36)     NOT NULL COMMENT 'Resolved id of a KI pinning this TNLR as input',
  PRIMARY KEY (`input_type`, `input_name`, `input_lang`, `input_major`, `successor_id`),
  KEY `idx_by_input` (`input_type`, `input_name`, `input_lang`, `input_major`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Articles are `type = 'article'` rows in AGORA_NODE (same versioned document model as
-- KIs); they cite KIs via `content.:inputs`. No separate article table.
