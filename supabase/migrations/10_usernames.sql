-- ============================================================================
-- 10_usernames.sql — Changeable usernames, and more than one per account
--
-- `profiles.username` was decided once at sign-up and never again. This adds a
-- table of usernames owned by an account, one of which is primary.
--
-- Two policy decisions are baked into the shape here.
--
-- A username is never released. A row in this table is a permanent claim: when
-- someone stops using a handle it is deactivated, not deleted, so nobody else
-- can ever take it. That is what makes changing a username safe — a handle that
-- could be re-registered by a stranger is a handle that can receive messages
-- meant for the person who gave it up. The global UNIQUE on `username` is what
-- enforces this; there is deliberately no cleanup job.
--
-- There is no rate limit. Since old handles stay claimed forever, changing
-- often costs the namespace nothing beyond the user's own allowance, and the
-- usual reason to throttle changes — churning handles to dodge blocks or
-- impersonate — is already prevented by the claims being permanent.
--
-- `profiles.username` stays, mirroring whichever row is primary. Everything
-- that already joins on it (search, channels, chat lists) keeps working.
-- ============================================================================

CREATE TABLE IF NOT EXISTS user_usernames (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  username TEXT NOT NULL,
  -- The one shown on the profile. Exactly one per account, enforced below.
  is_primary BOOLEAN NOT NULL DEFAULT FALSE,
  -- FALSE means "given up but still mine": it no longer routes anywhere and
  -- does not count against the allowance, and it can never be claimed again.
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  -- Permanent claim. Case is not part of identity, so store lowercase only and
  -- let this UNIQUE do the work rather than a case-insensitive index.
  CONSTRAINT user_usernames_unique UNIQUE (username),
  CONSTRAINT user_usernames_format CHECK (username ~ '^[a-z0-9_]{3,32}$')
);

CREATE INDEX IF NOT EXISTS idx_user_usernames_user ON user_usernames(user_id);
CREATE INDEX IF NOT EXISTS idx_user_usernames_active
  ON user_usernames(user_id) WHERE is_active;

-- One primary per account. A partial unique index rather than a constraint,
-- because the rule only applies to the rows where is_primary is true.
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_usernames_one_primary
  ON user_usernames(user_id) WHERE is_primary;

-- ---------------------------------------------------------------------------
-- Allowance
--
-- Three live handles per account. Checked in a trigger because a CHECK cannot
-- count sibling rows.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION enforce_username_allowance()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  active_count INTEGER;
BEGIN
  IF NEW.is_active THEN
    SELECT COUNT(*) INTO active_count
    FROM user_usernames
    WHERE user_id = NEW.user_id AND is_active AND id <> NEW.id;

    IF active_count >= 3 THEN
      RAISE EXCEPTION 'username_allowance_exceeded'
        USING HINT = 'Remove one of your usernames before adding another.';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_username_allowance ON user_usernames;
CREATE TRIGGER trg_username_allowance
  BEFORE INSERT OR UPDATE ON user_usernames
  FOR EACH ROW EXECUTE FUNCTION enforce_username_allowance();

-- ---------------------------------------------------------------------------
-- Keep profiles.username pointing at the primary
--
-- So every existing join keeps resolving without being taught about this table.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION sync_primary_username()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.is_primary AND NEW.is_active THEN
    UPDATE profiles SET username = NEW.username WHERE id = NEW.user_id;
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_sync_primary_username ON user_usernames;
CREATE TRIGGER trg_sync_primary_username
  AFTER INSERT OR UPDATE ON user_usernames
  FOR EACH ROW EXECUTE FUNCTION sync_primary_username();

-- ---------------------------------------------------------------------------
-- Backfill
--
-- Every existing profile's username becomes its primary claim, so the table is
-- the whole truth from the moment it exists rather than only covering handles
-- chosen after this migration.
-- ---------------------------------------------------------------------------
INSERT INTO user_usernames (user_id, username, is_primary, is_active)
SELECT p.id, lower(p.username), TRUE, TRUE
FROM profiles p
WHERE p.username IS NOT NULL
  AND lower(p.username) ~ '^[a-z0-9_]{3,32}$'
ON CONFLICT (username) DO NOTHING;

-- ---------------------------------------------------------------------------
-- RLS
--
-- Readable by everyone, because resolving "who is @name" is what the table is
-- for and the same is already true of profiles. Writable only by its owner.
-- ---------------------------------------------------------------------------
ALTER TABLE user_usernames ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Usernames are readable by authenticated users" ON user_usernames;
CREATE POLICY "Usernames are readable by authenticated users" ON user_usernames
  FOR SELECT TO authenticated USING (TRUE);

DROP POLICY IF EXISTS "Users can claim their own usernames" ON user_usernames;
CREATE POLICY "Users can claim their own usernames" ON user_usernames
  FOR INSERT TO authenticated WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can change their own usernames" ON user_usernames;
CREATE POLICY "Users can change their own usernames" ON user_usernames
  FOR UPDATE TO authenticated USING (auth.uid() = user_id);

-- Deliberately no DELETE policy. Giving up a handle sets is_active = FALSE;
-- deleting the row would release the claim, which is the thing this table
-- exists to prevent.
