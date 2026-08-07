-- ============================================================================
-- 09_channel_reactions_limit.sql — Let a channel allow up to ten reactions
--
-- 07_channels.sql capped `allowed_reactions` at six because the picker drew a
-- 3x2 grid. The picker later moved to a wrapping grid and the client cap went
-- to ten (Channel.MAX_REACTIONS), but this constraint never followed. The
-- result was an owner selecting seven reactions, seeing the UI accept all
-- seven, and getting
--
--     new row for relation "channels" violates check constraint
--     "channels_reactions_limit"
--
-- back from Postgres on save. The server was right and the client was wrong;
-- this migration moves the server to where the client already is.
--
-- Widening a CHECK is safe for existing rows by definition — every row that
-- satisfied 1..6 satisfies 1..10.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- The count limit
-- ---------------------------------------------------------------------------
ALTER TABLE channels DROP CONSTRAINT IF EXISTS channels_reactions_limit;

ALTER TABLE channels ADD CONSTRAINT channels_reactions_limit
  -- COALESCE matters: array_length('{}', 1) is NULL, and a NULL CHECK passes —
  -- without it an empty reaction list would slip through.
  CHECK (COALESCE(array_length(allowed_reactions, 1), 0) BETWEEN 1 AND 10);

COMMENT ON CONSTRAINT channels_reactions_limit ON channels IS
  'Kept in step with Channel.MAX_REACTIONS on the client. Changing one without the other is what broke saves before.';

-- ---------------------------------------------------------------------------
-- No duplicates
--
-- A duplicate emoji renders as two identical pills that split one post's
-- reaction count between them. The client dedupes before sending, but it is
-- the client that was wrong last time, so the rule belongs here too.
--
-- CHECK cannot contain a subquery, hence the IMMUTABLE helper. It is immutable
-- in the strict sense: same array in, same answer out, no table access.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION text_array_is_distinct(arr TEXT[])
RETURNS BOOLEAN
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
  SELECT COALESCE(array_length(arr, 1), 0) = (SELECT COUNT(DISTINCT e) FROM unnest(arr) AS e);
$$;

-- Any row that already holds duplicates has to be cleaned before the constraint
-- can be trusted, or the ALTER below fails outright. WITH ORDINALITY preserves
-- the owner's chosen display order while dropping the later copy of a repeat.
UPDATE channels c
SET allowed_reactions = deduped.arr
FROM (
  SELECT ch.id,
         ARRAY(
           SELECT emoji
           FROM (
             SELECT DISTINCT ON (u.emoji) u.emoji, u.ord
             FROM unnest(ch.allowed_reactions) WITH ORDINALITY AS u(emoji, ord)
             ORDER BY u.emoji, u.ord
           ) first_occurrence
           ORDER BY ord
         ) AS arr
  FROM channels ch
) deduped
WHERE c.id = deduped.id
  AND c.allowed_reactions IS DISTINCT FROM deduped.arr;

ALTER TABLE channels DROP CONSTRAINT IF EXISTS channels_reactions_distinct;

ALTER TABLE channels ADD CONSTRAINT channels_reactions_distinct
  CHECK (text_array_is_distinct(allowed_reactions));

COMMENT ON CONSTRAINT channels_reactions_distinct ON channels IS
  'Two identical pills would split one post''s count between them.';
