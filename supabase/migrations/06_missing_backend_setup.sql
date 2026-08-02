-- Fills in backend objects the Android client already calls/expects but that were never
-- actually created by a migration, so these features currently fail silently (or with an
-- opaque Postgres/storage error) against a fresh Supabase project:
--   1. profiles.fcm_token        — push notifications read/write this column
--   2. versions table            — app self-update check (UpdateManager.kt); 02_versions_checksum.sql
--                                   only ALTERs it, assuming it already exists
--   3. delete_chat_for_all RPC   — ChatRepository.deleteChat() calls this by name
--   4. avatars / chat_media buckets — never created, so avatar upload and media sending
--                                       have nowhere to write to

-- 1. Push notification token
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS fcm_token TEXT;

-- 2. Self-update version feed. Anyone can read it (it's just "what's the latest version"),
-- but there is deliberately no INSERT/UPDATE policy for regular users — publish a new row
-- yourself via the Supabase dashboard's table editor (or the SQL editor) when you ship a
-- build; the service-role key used by your own tooling bypasses RLS automatically.
CREATE TABLE IF NOT EXISTS versions (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  version TEXT NOT NULL,
  description TEXT DEFAULT '',
  google_link TEXT NOT NULL,
  sha256 TEXT,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE versions ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Anyone can read version info" ON versions;
CREATE POLICY "Anyone can read version info" ON versions
  FOR SELECT USING (true);

-- 3. Chat deletion RPC. The "chats" DELETE policy from 01_schema.sql already scopes deletion
-- to chat participants, and messages/chat_participants cascade automatically via their
-- existing ON DELETE CASCADE foreign keys — this function just gives the client a single
-- named call instead of a raw delete, with its own explicit participant check.
CREATE OR REPLACE FUNCTION delete_chat_for_all(p_chat_id UUID)
RETURNS VOID AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM chat_participants
    WHERE chat_id = p_chat_id AND user_id = auth.uid()
  ) THEN
    RAISE EXCEPTION 'Not a participant of this chat';
  END IF;

  DELETE FROM chats WHERE id = p_chat_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 4. Storage buckets.
-- "avatars" is public: the client calls bucket.publicUrl(...) and stores that URL directly
-- in profiles.avatar_url for anyone to load, matching the existing "profiles are viewable by
-- everyone" policy.
INSERT INTO storage.buckets (id, name, public)
VALUES ('avatars', 'avatars', true)
ON CONFLICT (id) DO NOTHING;

-- "chat_media" is private: the client always downloads it with an Authorization bearer
-- token (MediaMessagePlayer.kt), and the file contents are themselves already end-to-end
-- encrypted client-side before upload (CryptoManager.encryptFile) — the bucket being
-- non-public is defense in depth, not the only thing protecting confidentiality here.
INSERT INTO storage.buckets (id, name, public)
VALUES ('chat_media', 'chat_media', false)
ON CONFLICT (id) DO NOTHING;

DROP POLICY IF EXISTS "Avatar images are publicly accessible" ON storage.objects;
CREATE POLICY "Avatar images are publicly accessible" ON storage.objects
  FOR SELECT USING (bucket_id = 'avatars');

DROP POLICY IF EXISTS "Authenticated users can upload avatars" ON storage.objects;
CREATE POLICY "Authenticated users can upload avatars" ON storage.objects
  FOR INSERT WITH CHECK (bucket_id = 'avatars' AND auth.role() = 'authenticated');

DROP POLICY IF EXISTS "Authenticated users can update their avatars" ON storage.objects;
CREATE POLICY "Authenticated users can update their avatars" ON storage.objects
  FOR UPDATE USING (bucket_id = 'avatars' AND auth.role() = 'authenticated');

DROP POLICY IF EXISTS "Authenticated users can read chat media" ON storage.objects;
CREATE POLICY "Authenticated users can read chat media" ON storage.objects
  FOR SELECT USING (bucket_id = 'chat_media' AND auth.role() = 'authenticated');

DROP POLICY IF EXISTS "Authenticated users can upload chat media" ON storage.objects;
CREATE POLICY "Authenticated users can upload chat media" ON storage.objects
  FOR INSERT WITH CHECK (bucket_id = 'chat_media' AND auth.role() = 'authenticated');
