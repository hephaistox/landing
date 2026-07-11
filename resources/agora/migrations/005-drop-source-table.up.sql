-- Sources become documents (Layer 1A).
--
-- Bibliographic sources are no longer a separate table: a source is now an ordinary
-- AGORA_DOCUMENT of `type='source'` (the shared *work*), owned by its cited author (an
-- AGORA_USER person, incl. login-less `external` ones — that provider value STAYS). A
-- citing document references it on its own side via `content.:source = {:name <source-cid>
-- :major :locator}`, resolved on read. So the `AGORA_SOURCE` table (and its per-citation
-- `{source-id, locator}` snapshot model) is obsolete.
--
-- Note: existing rows that still carry the OLD `content.:source` snapshot shape
-- (`{:source-id …}`) simply resolve to nothing now (graceful) — reseed to repopulate.
--
-- Apply manually (no runner):
--   clojure -M scripts/agora_db.clj resources/agora/migrations/005-drop-source-table.up.sql

DROP TABLE IF EXISTS `AGORA_SOURCE`;
