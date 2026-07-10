-- Denormalize content.:published-at into a sortable column, so recency queries, the
-- sitemap, date-archive hubs, and keyset-paginated sitemap chunks can ORDER BY / range on
-- it without decoding the content EDN blob per row.
--
-- Stored as the ISO-8601 UTC string (the same value already in content), which sorts
-- lexicographically = chronologically. Apply this BEFORE deploying the code that writes
-- the column, then backfill existing rows once with
-- `landing.agora.document-store/backfill-published-at!`.
ALTER TABLE `AGORA_DOCUMENT`
  ADD COLUMN `published_at` VARCHAR(32) NULL
    COMMENT 'denormalized from content.:published-at (ISO-8601 UTC; sortable)',
  ADD KEY `idx_doc_published` (`published_at`);
