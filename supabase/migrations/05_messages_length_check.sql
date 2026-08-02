-- Defense-in-depth: the Android client caps message input at 4000 characters, but
-- nothing stopped an arbitrarily large value being written directly to this column.
-- 20000 gives generous headroom over AES-GCM + base64 overhead for a 4000-char message.
ALTER TABLE messages ADD CONSTRAINT ciphertext_length_check CHECK (length(ciphertext) <= 20000);
