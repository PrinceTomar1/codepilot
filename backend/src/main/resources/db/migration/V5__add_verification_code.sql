-- Adds a short numeric code alongside the existing link-based verification_token, so a user can
-- type the code into the app instead of needing to click through the email link. Shares the same
-- expiry as the token (verification_token_expires_at) -- both are generated and invalidated together.
ALTER TABLE users
    ADD COLUMN verification_code VARCHAR(6);
