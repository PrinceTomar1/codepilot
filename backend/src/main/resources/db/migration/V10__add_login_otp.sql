-- Adds passwordless login: request a one-time code by email, then sign in with it instead of a
-- password. Separate columns from verification_code (V5) deliberately -- they serve different
-- purposes with different consequences if confused (this one grants a live session token, that
-- one only flips emailVerified), so a stale/reused verification code must never double as a
-- login code, and vice versa.
ALTER TABLE users
    ADD COLUMN login_code VARCHAR(6),
    ADD COLUMN login_code_expires_at TIMESTAMPTZ;
