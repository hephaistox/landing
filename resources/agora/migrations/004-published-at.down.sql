-- Drop the denormalized published_at column (its index goes with it).
ALTER TABLE `AGORA_DOCUMENT` DROP COLUMN `published_at`;
