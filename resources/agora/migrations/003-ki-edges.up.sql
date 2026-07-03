-- Agora / Logos — Slice 2, #29
-- KI edges: an input KI implies an output KI.
--
-- Edges reference each endpoint by its identity at MAJOR granularity only
-- (type, name, major) — never the minor. This is deliberate: a KI-B that
-- depends on KI-A follows the concept, not a frozen minor version. Resolving a
-- (type, name, major) reference to the concrete latest-minor row is a query
-- concern added in Slice 3 (#30); at this stage only minor 0 exists.
--
-- No foreign keys: (type, name, major) is not unique in AGORA_KI (many minors
-- share it), so integrity is enforced at query/authoring time, not by the
-- schema (matches the spec's "referential integrity as a query property").

CREATE TABLE IF NOT EXISTS `AGORA_KI_EDGE` (
  `id`           CHAR(36)     NOT NULL COMMENT 'UUID, edge identity',
  `input_type`   ENUM('derived','verifiable-claim','postulate','stance','belief','credo')
                              NOT NULL COMMENT 'Input KI type',
  `input_name`   VARCHAR(255) NOT NULL COMMENT 'Input KI name',
  `input_major`  INT UNSIGNED NOT NULL COMMENT 'Input KI major version',
  `output_type`  ENUM('derived','verifiable-claim','postulate','stance','belief','credo')
                              NOT NULL COMMENT 'Output KI type',
  `output_name`  VARCHAR(255) NOT NULL COMMENT 'Output KI name',
  `output_major` INT UNSIGNED NOT NULL COMMENT 'Output KI major version',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_edge` (`input_type`, `input_name`, `input_major`,
                        `output_type`, `output_name`, `output_major`),
  KEY `idx_edge_by_output` (`output_type`, `output_name`, `output_major`),
  KEY `idx_edge_by_input`  (`input_type`, `input_name`, `input_major`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
