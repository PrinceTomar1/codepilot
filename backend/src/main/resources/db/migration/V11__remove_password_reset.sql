-- Forgot/reset-password was removed as a feature -- passwordless login (a one-time emailed
-- code, see V10) covers the "I don't remember my password" case instead, so this is dead
-- capability rather than dormant-but-wanted. Drops what V9 added.
DROP INDEX IF EXISTS idx_users_reset_token;

ALTER TABLE users
    DROP COLUMN IF EXISTS reset_token,
    DROP COLUMN IF EXISTS reset_token_expires_at;
