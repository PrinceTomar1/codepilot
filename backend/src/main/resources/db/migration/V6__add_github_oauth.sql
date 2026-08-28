-- GitHub OAuth login/signup: links a user to their GitHub identity and stores their OAuth
-- access token (encrypted, same as manually-pasted PATs) so it can be used to list/connect their
-- repositories without asking them to paste a token by hand.
ALTER TABLE users
    ADD COLUMN github_id BIGINT UNIQUE,
    ADD COLUMN github_username VARCHAR(255),
    ADD COLUMN github_access_token_encrypted TEXT;
