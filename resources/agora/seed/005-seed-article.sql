-- Agora / Logos — Slice 3-bis, #31
-- One article seeded manually. Body stored content-addressed in AGORA_BLOB;
-- the article row references it by hash (SHA-256 of the exact body text below).

INSERT INTO `AGORA_BLOB` (`hash`, `content`) VALUES
  ('7c16072356a54dcffd9d7b1971c1b51d6162bca464ea2fbd0a5ec3c89e87efed',
   'Most knowledge tools store conclusions. This platform stores reasoning chains, one implication at a time.

The first knowledge item in this graph is a postulate: our collective confidence in any claim is partial and always evolving. From it follows a derived conclusion, that a knowledge platform should record graded confidence rather than forcing binary true or false verdicts.

This article is a placeholder seeded directly in the database. Later slices will let authors write articles whose terms and arguments link to the knowledge items that support them.');

INSERT INTO `AGORA_ARTICLE` (`id`, `title`, `body_hash`, `published_at`) VALUES
  ('00000000-0000-0000-0000-0000000000a1',
   'Reasoning, made legible',
   '7c16072356a54dcffd9d7b1971c1b51d6162bca464ea2fbd0a5ec3c89e87efed',
   '2026-07-03 00:00:00');
