-- The "messages" table has RLS enabled but no DELETE policy (see 01_schema.sql),
-- so any DELETE from the client is currently rejected by Postgres. The app's
-- "delete for everyone" feature relies on this working. Scope it to the original
-- sender only (tighter than "any chat participant"), matching what "delete for
-- everyone" should actually mean.
CREATE POLICY "Sender can delete their own messages" ON messages
  FOR DELETE USING (sender_id = auth.uid());
