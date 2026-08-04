-- ============================================================================
-- 08_key_backup.sql — Passphrase-protected backup of the identity private key
--
-- The problem this exists to solve: the EC-P256 private key that decrypts a
-- user's entire message history was generated on one device and stored nowhere
-- else. Uninstalling the app, losing the phone, or signing in somewhere new
-- meant that key was gone, and with it every message ever sent or received —
-- permanently, because ECDH is symmetric and the same key reads both sides.
--
-- The ciphertext itself was never the problem. Every message row in `messages`
-- is still here, intact. It was purely a key-availability problem, and this is
-- the key escrow that fixes it.
--
-- IMPORTANT — what the server can and cannot do with this table.
--
-- `wrapped_private_key` is the private key encrypted with AES-256-GCM under a
-- key derived from the user's recovery passphrase via PBKDF2. The passphrase is
-- never sent anywhere, never stored, and never recoverable from this row. A
-- full dump of this table gives an attacker a set of GCM ciphertexts and their
-- salts, and nothing else — they would have to run the KDF per guess per user,
-- which is the entire point of the iteration count being stored high.
--
-- This preserves end-to-end encryption. The server holds a blob it cannot open.
-- What it costs is the honest trade every E2EE backup makes: a forgotten
-- passphrase is unrecoverable, by anyone, including us. That is a property, not
-- a defect — a passphrase we could reset would be a passphrase an attacker
-- could reset.
--
-- The KDF parameters are stored per row rather than hardcoded in the client.
-- Iteration counts must rise as hardware gets faster, and a client that assumes
-- today's number cannot open a backup written next year.
-- ============================================================================

CREATE TABLE IF NOT EXISTS key_backups (
  user_id             UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,

  -- The public half, stored alongside so a restoring client can verify it
  -- unwrapped the right key before trusting it. Without this check a wrong
  -- passphrase that happened to pass GCM authentication would install a
  -- garbage key, and the failure would surface much later as unreadable
  -- messages rather than as "wrong passphrase".
  public_key          TEXT NOT NULL,

  -- base64(iv ‖ ciphertext ‖ tag) — AES-256-GCM over the PKCS#8 private key.
  wrapped_private_key TEXT NOT NULL,

  -- KDF parameters. Salt is per-user and random, so identical passphrases
  -- across two accounts produce unrelated wrapping keys and a precomputed
  -- table is worthless.
  kdf_salt            TEXT NOT NULL,
  kdf_iterations      INTEGER NOT NULL,
  kdf_algorithm       TEXT NOT NULL DEFAULT 'PBKDF2WithHmacSHA256',

  -- Shown in the UI as "backed up on ...", so somebody can tell at a glance
  -- whether the backup predates the messages they are trying to recover.
  created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  CONSTRAINT key_backup_iterations_sane CHECK (kdf_iterations >= 100000),
  CONSTRAINT key_backup_algorithm_known CHECK (
    kdf_algorithm IN ('PBKDF2WithHmacSHA256', 'PBKDF2WithHmacSHA512')
  )
);

ALTER TABLE key_backups ENABLE ROW LEVEL SECURITY;

-- One row, one owner, no exceptions. There is deliberately no policy allowing
-- anyone to read another user's backup: the blob is useless without the
-- passphrase, but handing out ciphertext to be attacked offline at leisure
-- would be an unforced error.
DROP POLICY IF EXISTS "own key backup readable" ON key_backups;
CREATE POLICY "own key backup readable" ON key_backups
  FOR SELECT USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "own key backup writable" ON key_backups;
CREATE POLICY "own key backup writable" ON key_backups
  FOR INSERT WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "own key backup updatable" ON key_backups;
CREATE POLICY "own key backup updatable" ON key_backups
  FOR UPDATE USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);

-- Deleting your own backup is allowed: it is the "stop escrowing my key"
-- control, and a backup a user cannot withdraw is not one they consented to in
-- any meaningful sense.
DROP POLICY IF EXISTS "own key backup deletable" ON key_backups;
CREATE POLICY "own key backup deletable" ON key_backups
  FOR DELETE USING (auth.uid() = user_id);

CREATE OR REPLACE FUNCTION touch_key_backup_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  NEW.updated_at := NOW();
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS key_backups_touch_updated_at ON key_backups;
CREATE TRIGGER key_backups_touch_updated_at
  BEFORE UPDATE ON key_backups
  FOR EACH ROW EXECUTE FUNCTION touch_key_backup_updated_at();

-- ---------------------------------------------------------------------------
-- Post-conditions
-- ---------------------------------------------------------------------------
SELECT 'key_backups_table' AS check_name,
       to_regclass('public.key_backups') IS NOT NULL AS ok,
       'key_backups exists' AS detail
UNION ALL
SELECT 'key_backups_rls',
       relrowsecurity,
       'row level security is on'
  FROM pg_class WHERE relname = 'key_backups'
UNION ALL
SELECT 'key_backups_policies',
       count(*) = 4,
       count(*)::text || ' policies (select/insert/update/delete, all owner-scoped)'
  FROM pg_policies WHERE tablename = 'key_backups';
