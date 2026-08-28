-- Per-file content hashes, so the AI service can skip re-embedding unchanged
-- files on incremental (re-)indexing runs instead of always doing a full wipe.
CREATE TABLE indexed_files (
    repository_id  UUID NOT NULL REFERENCES code_repositories (id) ON DELETE CASCADE,
    file_path      TEXT NOT NULL,
    content_sha    VARCHAR(64) NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (repository_id, file_path)
);
