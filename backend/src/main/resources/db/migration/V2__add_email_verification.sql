-- Email verification for new accounts.
ALTER TABLE users
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN verification_token VARCHAR(255),
    ADD COLUMN verification_token_expires_at TIMESTAMPTZ;

CREATE UNIQUE INDEX idx_users_verification_token ON users (verification_token)
    WHERE verification_token IS NOT NULL;
