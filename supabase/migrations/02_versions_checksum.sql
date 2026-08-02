-- Adds a checksum column to the (untracked-elsewhere) "versions" table used by
-- the Android app's self-update flow (see app/src/main/java/com/neveroff/util/UpdateManager.kt).
-- The app now refuses to install an update unless its SHA-256 matches this column,
-- so every new row inserted into "versions" going forward MUST have sha256 populated
-- with the SHA-256 (lowercase hex) of the published APK, or the update will be rejected
-- client-side with "Update is missing a verified checksum".
ALTER TABLE versions ADD COLUMN IF NOT EXISTS sha256 TEXT;
