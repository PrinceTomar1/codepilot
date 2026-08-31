-- Adds password-reset support. Separate token from verification_token (V2) deliberately -- they
-- have different lifetimes and security implications (a reset token grants a password change, a
-- verification token only flips a boolean), so sharing one column would let a still-valid old
-- verification link double as a password-reset link, which is not a coincidence worth allowing.
ALTER TABLE users
    ADD COLUMN reset_token VARCHAR(255),
    ADD COLUMN reset_token_expires_at TIMESTAMPTZ;

CREATE UNIQUE INDEX idx_users_reset_token ON users (reset_token) WHERE reset_token IS NOT NULL;
