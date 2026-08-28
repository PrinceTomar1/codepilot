-- CodePilot initial schema.
-- This schema is shared with the Python AI service, which reads/writes code_chunks directly
-- (no JPA entity for that table on the Java side - Flyway just needs to create it).

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(320) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    name          VARCHAR(255),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- code_repositories
-- ---------------------------------------------------------------------------
CREATE TABLE code_repositories (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    github_owner            VARCHAR(255) NOT NULL,
    github_repo             VARCHAR(255) NOT NULL,
    github_repo_id          BIGINT,
    default_branch          VARCHAR(255),
    webhook_secret          VARCHAR(255),
    access_token_encrypted  TEXT,
    status                  VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    indexed_at              TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_code_repositories_status
        CHECK (status IN ('PENDING', 'INDEXING', 'INDEXED', 'FAILED'))
);

CREATE INDEX idx_code_repositories_user_id ON code_repositories (user_id);
CREATE INDEX idx_code_repositories_owner_repo ON code_repositories (github_owner, github_repo);

-- ---------------------------------------------------------------------------
-- index_jobs
-- ---------------------------------------------------------------------------
CREATE TABLE index_jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id   UUID NOT NULL REFERENCES code_repositories (id) ON DELETE CASCADE,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    files_indexed   INT NOT NULL DEFAULT 0,
    chunks_created  INT NOT NULL DEFAULT 0,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    error           TEXT
);

CREATE INDEX idx_index_jobs_repository_id ON index_jobs (repository_id);

-- ---------------------------------------------------------------------------
-- code_chunks (owned by the Python AI service; created here so the schema exists up front)
-- ---------------------------------------------------------------------------
CREATE TABLE code_chunks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id   UUID NOT NULL REFERENCES code_repositories (id) ON DELETE CASCADE,
    file_path       TEXT NOT NULL,
    language        VARCHAR(50),
    start_line      INT,
    end_line        INT,
    content         TEXT,
    embedding       vector(1536),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_code_chunks_repository_id ON code_chunks (repository_id);

-- ---------------------------------------------------------------------------
-- qa_history
-- ---------------------------------------------------------------------------
CREATE TABLE qa_history (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id   UUID NOT NULL REFERENCES code_repositories (id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    question        TEXT NOT NULL,
    answer          TEXT,
    citations       JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_qa_history_repository_id ON qa_history (repository_id, created_at DESC);
CREATE INDEX idx_qa_history_user_id ON qa_history (user_id);

-- ---------------------------------------------------------------------------
-- pull_requests
-- ---------------------------------------------------------------------------
CREATE TABLE pull_requests (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id     UUID NOT NULL REFERENCES code_repositories (id) ON DELETE CASCADE,
    github_pr_number  INT NOT NULL,
    title             TEXT,
    author            VARCHAR(255),
    head_sha          VARCHAR(64),
    base_sha          VARCHAR(64),
    status            VARCHAR(30),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_pull_requests_repo_pr_number UNIQUE (repository_id, github_pr_number)
);

CREATE INDEX idx_pull_requests_repository_id ON pull_requests (repository_id);

-- ---------------------------------------------------------------------------
-- review_reports
-- ---------------------------------------------------------------------------
CREATE TABLE review_reports (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pull_request_id   UUID NOT NULL REFERENCES pull_requests (id) ON DELETE CASCADE,
    overall_summary   TEXT,
    bugs              JSONB,
    security          JSONB,
    code_smells       JSONB,
    missing_tests     JSONB,
    performance       JSONB,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_review_reports_pull_request_id ON review_reports (pull_request_id);

-- ---------------------------------------------------------------------------
-- onboarding_docs
-- ---------------------------------------------------------------------------
CREATE TABLE onboarding_docs (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id           UUID NOT NULL REFERENCES code_repositories (id) ON DELETE CASCADE,
    architecture_overview   TEXT,
    important_modules       JSONB,
    setup_instructions      TEXT,
    data_flow               TEXT,
    read_first              JSONB,
    generated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_onboarding_docs_repository_id ON onboarding_docs (repository_id, generated_at DESC);
