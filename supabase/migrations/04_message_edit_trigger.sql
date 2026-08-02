-- The "messages" UPDATE RLS policy (01_schema.sql) only checks that the caller is a
-- participant of the chat, not that they're the original sender. That's required so
-- any participant can flip `status` (delivered/read receipts) on a message they didn't
-- send, but it also means any participant could rewrite `ciphertext`/`msg_key` on
-- someone else's message. A blanket "sender only" UPDATE policy would break read
-- receipts, so this uses a column-aware trigger instead: it only blocks changes to
-- `ciphertext`/`msg_key` made by someone other than the original sender, and leaves
-- `status` updates (by any participant) untouched.
CREATE OR REPLACE FUNCTION prevent_message_content_tamper()
RETURNS TRIGGER AS $$
BEGIN
  IF (NEW.ciphertext IS DISTINCT FROM OLD.ciphertext OR NEW.msg_key IS DISTINCT FROM OLD.msg_key)
     AND OLD.sender_id != auth.uid() THEN
    RAISE EXCEPTION 'Only the original sender can edit message content';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS enforce_message_content_ownership ON messages;
CREATE TRIGGER enforce_message_content_ownership
  BEFORE UPDATE ON messages
  FOR EACH ROW EXECUTE FUNCTION prevent_message_content_tamper();
