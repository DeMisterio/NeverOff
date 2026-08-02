-- ============================================================================
-- 07_channels.sql — Channels (broadcast feeds)
--
-- A channel is a one-to-many feed: the owner posts, everyone else reads.
--
-- IMPORTANT — posts are NOT end-to-end encrypted, unlike 1:1 messages.
-- A channel post has no single recipient to derive a shared secret with: it is
-- read by everyone who opens the link, including people who subscribe months
-- after it was written. So posts are stored in plaintext and kept permanently
-- (they are the channel's history, not ephemeral chat).
--
-- "Private" means UNLISTED, not access-controlled: a private channel is
-- excluded from search and is reachable only by its n.me link. Anyone holding
-- that link can read it. Nothing secret belongs in a channel.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Channels
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS channels (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  description TEXT,
  avatar_url TEXT,
  -- The n.me/<username> slug. Public channels get a user-chosen one; private
  -- channels get a random unguessable slug so "share by link" still works.
  username TEXT NOT NULL UNIQUE,
  is_public BOOLEAN NOT NULL DEFAULT TRUE,
  -- Reactions the owner allows on posts, in display order. Max 6 (3x2 grid).
  allowed_reactions TEXT[] NOT NULL DEFAULT ARRAY['❤️', '👍', '👎', '🤡', '⚡', '😍'],
  subscriber_count INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  CONSTRAINT channels_username_format CHECK (username ~ '^[a-z0-9_]{3,32}$'),
  CONSTRAINT channels_name_length CHECK (char_length(name) BETWEEN 1 AND 64),
  CONSTRAINT channels_description_length CHECK (description IS NULL OR char_length(description) <= 500),
  -- COALESCE matters: array_length('{}', 1) is NULL, and a NULL CHECK passes —
  -- without it an empty reaction list would slip through.
  CONSTRAINT channels_reactions_limit CHECK (COALESCE(array_length(allowed_reactions, 1), 0) BETWEEN 1 AND 6)
);

CREATE INDEX IF NOT EXISTS idx_channels_owner ON channels(owner_id);
CREATE INDEX IF NOT EXISTS idx_channels_public ON channels(is_public) WHERE is_public;

-- ---------------------------------------------------------------------------
-- Social links shown on the channel profile
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS channel_links (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  channel_id UUID NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
  platform TEXT NOT NULL,
  url TEXT NOT NULL,
  position INTEGER NOT NULL DEFAULT 0,

  CONSTRAINT channel_links_platform CHECK (platform IN ('instagram', 'snapchat', 'tiktok', 'telegram')),
  CONSTRAINT channel_links_url_length CHECK (char_length(url) BETWEEN 1 AND 300)
);

CREATE INDEX IF NOT EXISTS idx_channel_links_channel ON channel_links(channel_id);

-- ---------------------------------------------------------------------------
-- Subscribers
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS channel_subscribers (
  channel_id UUID NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  subscribed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (channel_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_channel_subscribers_user ON channel_subscribers(user_id);

-- ---------------------------------------------------------------------------
-- Posts
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS channel_posts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  channel_id UUID NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
  author_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  -- Plaintext. Media posts reuse the chat "[IMAGE] <name> <key>" text format.
  content TEXT NOT NULL,
  view_count INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  edited_at TIMESTAMPTZ,

  CONSTRAINT channel_posts_content_length CHECK (char_length(content) BETWEEN 1 AND 8000)
);

CREATE INDEX IF NOT EXISTS idx_channel_posts_channel_created ON channel_posts(channel_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Views — one row per (post, user), so a re-read never double-counts
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS channel_post_views (
  post_id UUID NOT NULL REFERENCES channel_posts(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  viewed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (post_id, user_id)
);

-- ---------------------------------------------------------------------------
-- Reactions — one reaction per user per post (picking a new one replaces it)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS channel_post_reactions (
  post_id UUID NOT NULL REFERENCES channel_posts(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  emoji TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (post_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_channel_post_reactions_post ON channel_post_reactions(post_id);

-- ============================================================================
-- Counter triggers — keep subscriber_count / view_count denormalised so the
-- list and post rows never need an aggregate query per row.
-- ============================================================================

CREATE OR REPLACE FUNCTION bump_channel_subscriber_count()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    UPDATE channels SET subscriber_count = subscriber_count + 1 WHERE id = NEW.channel_id;
  ELSIF TG_OP = 'DELETE' THEN
    UPDATE channels SET subscriber_count = GREATEST(subscriber_count - 1, 0) WHERE id = OLD.channel_id;
  END IF;
  RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS trg_channel_subscriber_count ON channel_subscribers;
CREATE TRIGGER trg_channel_subscriber_count
  AFTER INSERT OR DELETE ON channel_subscribers
  FOR EACH ROW EXECUTE FUNCTION bump_channel_subscriber_count();

CREATE OR REPLACE FUNCTION bump_channel_post_view_count()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  UPDATE channel_posts SET view_count = view_count + 1 WHERE id = NEW.post_id;
  RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS trg_channel_post_view_count ON channel_post_views;
CREATE TRIGGER trg_channel_post_view_count
  AFTER INSERT ON channel_post_views
  FOR EACH ROW EXECUTE FUNCTION bump_channel_post_view_count();

-- ============================================================================
-- RPCs
-- ============================================================================

-- Records a view idempotently. ON CONFLICT DO NOTHING means the client can call
-- this freely (e.g. after every scroll) without ever inflating the count.
CREATE OR REPLACE FUNCTION register_post_view(p_post_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  IF auth.uid() IS NULL THEN
    RAISE EXCEPTION 'Not authenticated';
  END IF;
  INSERT INTO channel_post_views (post_id, user_id)
  VALUES (p_post_id, auth.uid())
  ON CONFLICT (post_id, user_id) DO NOTHING;
END;
$$;

-- Case-insensitive availability check used by the "create channel" link field.
CREATE OR REPLACE FUNCTION is_channel_username_available(p_username TEXT)
RETURNS BOOLEAN
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT NOT EXISTS (SELECT 1 FROM channels WHERE username = lower(p_username))
     AND NOT EXISTS (SELECT 1 FROM profiles WHERE lower(username) = lower(p_username));
$$;

-- ============================================================================
-- Row Level Security
-- ============================================================================

ALTER TABLE channels ENABLE ROW LEVEL SECURITY;
ALTER TABLE channel_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE channel_subscribers ENABLE ROW LEVEL SECURITY;
ALTER TABLE channel_posts ENABLE ROW LEVEL SECURITY;
ALTER TABLE channel_post_views ENABLE ROW LEVEL SECURITY;
ALTER TABLE channel_post_reactions ENABLE ROW LEVEL SECURITY;

-- Channels: readable by any signed-in user (a link-holder must be able to
-- resolve a private channel before subscribing). Only the owner can write.
DROP POLICY IF EXISTS "Channels are readable by authenticated users" ON channels;
CREATE POLICY "Channels are readable by authenticated users" ON channels
  FOR SELECT USING (auth.uid() IS NOT NULL);

DROP POLICY IF EXISTS "Users can create their own channels" ON channels;
CREATE POLICY "Users can create their own channels" ON channels
  FOR INSERT WITH CHECK (auth.uid() = owner_id);

DROP POLICY IF EXISTS "Owners can update their channels" ON channels;
CREATE POLICY "Owners can update their channels" ON channels
  FOR UPDATE USING (auth.uid() = owner_id) WITH CHECK (auth.uid() = owner_id);

DROP POLICY IF EXISTS "Owners can delete their channels" ON channels;
CREATE POLICY "Owners can delete their channels" ON channels
  FOR DELETE USING (auth.uid() = owner_id);

-- Links
DROP POLICY IF EXISTS "Channel links are readable by authenticated users" ON channel_links;
CREATE POLICY "Channel links are readable by authenticated users" ON channel_links
  FOR SELECT USING (auth.uid() IS NOT NULL);

DROP POLICY IF EXISTS "Owners manage channel links" ON channel_links;
CREATE POLICY "Owners manage channel links" ON channel_links
  FOR ALL USING (
    EXISTS (SELECT 1 FROM channels c WHERE c.id = channel_links.channel_id AND c.owner_id = auth.uid())
  ) WITH CHECK (
    EXISTS (SELECT 1 FROM channels c WHERE c.id = channel_links.channel_id AND c.owner_id = auth.uid())
  );

-- Subscribers: everyone can see who is subscribed (counts), but you may only
-- add/change/remove your own subscription.
DROP POLICY IF EXISTS "Subscriptions are readable by authenticated users" ON channel_subscribers;
CREATE POLICY "Subscriptions are readable by authenticated users" ON channel_subscribers
  FOR SELECT USING (auth.uid() IS NOT NULL);

DROP POLICY IF EXISTS "Users can subscribe themselves" ON channel_subscribers;
CREATE POLICY "Users can subscribe themselves" ON channel_subscribers
  FOR INSERT WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can update their own subscription" ON channel_subscribers;
CREATE POLICY "Users can update their own subscription" ON channel_subscribers
  FOR UPDATE USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can unsubscribe themselves" ON channel_subscribers;
CREATE POLICY "Users can unsubscribe themselves" ON channel_subscribers
  FOR DELETE USING (auth.uid() = user_id);

-- Posts: readable by any signed-in user; only the channel owner may write.
-- Viewers can never delete a post. The owner can delete an individual post,
-- but the app deliberately exposes no "clear all history" action for channels
-- (unlike 1:1 chats) — a channel's history is its content, not a transcript.
DROP POLICY IF EXISTS "Channel posts are readable by authenticated users" ON channel_posts;
CREATE POLICY "Channel posts are readable by authenticated users" ON channel_posts
  FOR SELECT USING (auth.uid() IS NOT NULL);

DROP POLICY IF EXISTS "Channel owners can post" ON channel_posts;
CREATE POLICY "Channel owners can post" ON channel_posts
  FOR INSERT WITH CHECK (
    auth.uid() = author_id
    AND EXISTS (SELECT 1 FROM channels c WHERE c.id = channel_posts.channel_id AND c.owner_id = auth.uid())
  );

DROP POLICY IF EXISTS "Channel owners can edit their posts" ON channel_posts;
CREATE POLICY "Channel owners can edit their posts" ON channel_posts
  FOR UPDATE USING (
    EXISTS (SELECT 1 FROM channels c WHERE c.id = channel_posts.channel_id AND c.owner_id = auth.uid())
  ) WITH CHECK (
    EXISTS (SELECT 1 FROM channels c WHERE c.id = channel_posts.channel_id AND c.owner_id = auth.uid())
  );

DROP POLICY IF EXISTS "Channel owners can delete their posts" ON channel_posts;
CREATE POLICY "Channel owners can delete their posts" ON channel_posts
  FOR DELETE USING (
    EXISTS (SELECT 1 FROM channels c WHERE c.id = channel_posts.channel_id AND c.owner_id = auth.uid())
  );

-- Views: a user may only ever write their own view row.
DROP POLICY IF EXISTS "Views are readable by authenticated users" ON channel_post_views;
CREATE POLICY "Views are readable by authenticated users" ON channel_post_views
  FOR SELECT USING (auth.uid() IS NOT NULL);

DROP POLICY IF EXISTS "Users register their own views" ON channel_post_views;
CREATE POLICY "Users register their own views" ON channel_post_views
  FOR INSERT WITH CHECK (auth.uid() = user_id);

-- Reactions: readable by everyone, writable only for yourself.
DROP POLICY IF EXISTS "Reactions are readable by authenticated users" ON channel_post_reactions;
CREATE POLICY "Reactions are readable by authenticated users" ON channel_post_reactions
  FOR SELECT USING (auth.uid() IS NOT NULL);

DROP POLICY IF EXISTS "Users can add their own reaction" ON channel_post_reactions;
CREATE POLICY "Users can add their own reaction" ON channel_post_reactions
  FOR INSERT WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can change their own reaction" ON channel_post_reactions;
CREATE POLICY "Users can change their own reaction" ON channel_post_reactions
  FOR UPDATE USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can remove their own reaction" ON channel_post_reactions;
CREATE POLICY "Users can remove their own reaction" ON channel_post_reactions
  FOR DELETE USING (auth.uid() = user_id);

-- ============================================================================
-- Realtime — live post delivery and live reaction counts
-- ============================================================================
DO $$
BEGIN
  BEGIN
    ALTER PUBLICATION supabase_realtime ADD TABLE channel_posts;
  EXCEPTION WHEN duplicate_object THEN NULL;
  END;
  BEGIN
    ALTER PUBLICATION supabase_realtime ADD TABLE channel_post_reactions;
  EXCEPTION WHEN duplicate_object THEN NULL;
  END;
  BEGIN
    ALTER PUBLICATION supabase_realtime ADD TABLE channels;
  EXCEPTION WHEN duplicate_object THEN NULL;
  END;
END $$;

-- ============================================================================
-- Verification — the last statement returns one row per check.
-- Every `ok` column should read true. Anything false means that piece did not
-- get created and the app will fail on that feature specifically.
-- ============================================================================
SELECT 'tables' AS check_name,
       count(*) = 6 AS ok,
       count(*)::text || ' of 6' AS detail
  FROM information_schema.tables
 WHERE table_schema = 'public'
   AND table_name IN ('channels', 'channel_links', 'channel_subscribers',
                      'channel_posts', 'channel_post_views', 'channel_post_reactions')
UNION ALL
SELECT 'rls_enabled',
       bool_and(rowsecurity),
       count(*) FILTER (WHERE rowsecurity)::text || ' of ' || count(*)::text
  FROM pg_tables
 WHERE schemaname = 'public'
   AND tablename IN ('channels', 'channel_links', 'channel_subscribers',
                     'channel_posts', 'channel_post_views', 'channel_post_reactions')
UNION ALL
SELECT 'functions',
       count(*) = 4,
       string_agg(proname, ', ' ORDER BY proname)
  FROM pg_proc
 WHERE pronamespace = 'public'::regnamespace
   AND proname IN ('register_post_view', 'is_channel_username_available',
                   'bump_channel_subscriber_count', 'bump_channel_post_view_count')
UNION ALL
SELECT 'triggers',
       count(*) = 2,
       count(*)::text || ' of 2'
  FROM pg_trigger
 WHERE NOT tgisinternal
   AND tgname IN ('trg_channel_subscriber_count', 'trg_channel_post_view_count')
UNION ALL
SELECT 'realtime',
       count(*) = 3,
       string_agg(tablename, ', ' ORDER BY tablename)
  FROM pg_publication_tables
 WHERE pubname = 'supabase_realtime'
   AND tablename IN ('channels', 'channel_posts', 'channel_post_reactions')
UNION ALL
SELECT 'profiles.fcm_token',
       count(*) = 1,
       'needed by channel push'
  FROM information_schema.columns
 WHERE table_schema = 'public' AND table_name = 'profiles' AND column_name = 'fcm_token'
UNION ALL
SELECT 'avatars_bucket',
       count(*) = 1,
       'channel pictures reuse this bucket'
  FROM storage.buckets
 WHERE id = 'avatars';
