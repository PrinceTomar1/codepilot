# Database

PostgreSQL 16+ with the `pgvector` extension, owned by two writers that split the schema cleanly:
Flyway (Spring Boot backend) creates and evolves every table; the Python `ai-service` only ever
reads/writes rows in `code_chunks` and `indexed_files` (no ORM migrations on the Python side, by
design — one schema owner avoids the two services drifting out of sync).

Migrations live in `backend/src/main/resources/db/migration/`:

| Migration | What it added |
|---|---|
| `V1__init.sql` | Initial schema: `users`, `code_repositories`, `index_jobs`, `code_chunks`, `qa_history`, `pull_requests`, `review_reports`, `onboarding_docs` |
| `V2__add_email_verification.sql` | `email_verified`, `verification_token`, `verification_token_expires_at` on `users` |
| `V3__add_indexed_files.sql` | `indexed_files` table — per-file content hashes backing incremental indexing |

## Tables

### `users`
Local email/password accounts (bcrypt hash). `email_verified` gates login — see
[Auth flow](#auth-notes) below.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `email` | VARCHAR(320) UNIQUE | |
| `password_hash` | VARCHAR(255) | bcrypt |
| `name` | VARCHAR(255) | |
| `email_verified` | BOOLEAN | default false |
| `verification_token` | VARCHAR(255) | nullable; cleared on verify |
| `verification_token_expires_at` | TIMESTAMPTZ | 24h from issuance |
| `created_at` | TIMESTAMPTZ | |

### `code_repositories`
One row per connected GitHub repo. `access_token_encrypted` holds an AES-256-GCM ciphertext
(`EncryptionService`) of the GitHub token used to call the GitHub API on the owner's behalf —
the plaintext token never touches the frontend and is only decrypted in-memory when a GitHub
API call is actually made. `webhook_secret` is a per-repo random secret used to verify inbound
webhook HMAC signatures.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `user_id` | UUID FK → users, `ON DELETE CASCADE` | owner |
| `github_owner`, `github_repo` | VARCHAR | |
| `github_repo_id` | BIGINT | GitHub's numeric repo ID, filled in on first index |
| `default_branch` | VARCHAR | |
| `webhook_secret` | VARCHAR | |
| `access_token_encrypted` | TEXT | AES-256-GCM ciphertext |
| `status` | VARCHAR(20) | `PENDING \| INDEXING \| INDEXED \| FAILED` (CHECK constraint) |
| `indexed_at` | TIMESTAMPTZ | last successful full index |
| `created_at` | TIMESTAMPTZ | |

Indexes: `user_id` (list-my-repos), `(github_owner, github_repo)` (webhook repo lookup).

### `index_jobs`
One row per indexing run (initial connect, manual re-index, or webhook-triggered push
re-index), so the frontend can show indexing progress/history and the last error on failure.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `repository_id` | UUID FK → code_repositories, cascade | |
| `status` | VARCHAR(20) | `PENDING \| RUNNING \| COMPLETED \| FAILED` |
| `files_indexed`, `chunks_created` | INT | |
| `started_at`, `finished_at` | TIMESTAMPTZ | |
| `error` | TEXT | truncated to 4000 chars |

### `code_chunks` — owned by ai-service
The RAG index. Each row is one structurally-chunked piece of a source file (see
[`docs/rag.md`](rag.md#chunking) for how chunks are produced) plus its embedding vector.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `repository_id` | UUID FK → code_repositories, cascade | |
| `file_path` | TEXT | |
| `language` | VARCHAR(50) | |
| `start_line`, `end_line` | INT | 1-indexed, inclusive |
| `content` | TEXT | the chunk's raw source |
| `embedding` | `vector(1536)` | pgvector column, fixed dimension regardless of provider |
| `created_at` | TIMESTAMPTZ | |

Index: `repository_id` (every query is scoped to one repo). Similarity search uses pgvector's
cosine-distance operator (`<=>`) directly in the `ORDER BY` clause — see `VectorStore.similarity_search`.

### `indexed_files` — owned by ai-service
Backs incremental indexing: one row per file per repo holding its last-seen content hash
(SHA-256 of the raw file content). On every `/index` call, the ai-service diffs incoming file
hashes against this table — matching hashes are skipped entirely (no re-chunk, no re-embed, no
delete/insert), changed hashes are re-chunked and re-embedded, and paths that disappear from the
incoming file list are treated as deletions (their chunks and hash row are removed).

| Column | Type | Notes |
|---|---|---|
| `repository_id` | UUID FK → code_repositories, cascade | part of composite PK |
| `file_path` | TEXT | part of composite PK |
| `content_sha` | VARCHAR(64) | SHA-256 hex digest |
| `updated_at` | TIMESTAMPTZ | |

### `qa_history`
One row per Q&A round-trip (see [`docs/rag.md`](rag.md)), so chat history can be replayed per
repo/user. `citations` is a JSONB array of `{file, startLine, endLine}` objects sourced directly
from the retrieved chunks, not parsed from the model's free-text answer.

Indexes: `(repository_id, created_at DESC)` (history list, newest first), `user_id`.

### `pull_requests`
One row per (repository, GitHub PR number), upserted on every relevant webhook delivery.
`status` tracks `PENDING_REVIEW → REVIEWED` or `REVIEW_FAILED`.

Unique constraint: `(repository_id, github_pr_number)` — a second delivery for the same PR
updates the existing row rather than creating a duplicate.

### `review_reports`
One row per completed AI review run. Findings are stored as five separate JSONB arrays
(`bugs`, `security`, `code_smells`, `missing_tests`, `performance`) rather than a generic
"findings" table, because the frontend renders them as fixed category tabs and the shape is
stable (see [`docs/agents.md`](agents.md) for the finding schema itself).

### `onboarding_docs`
One row per generated onboarding doc (repos can regenerate; `generated_at` orders history).
`important_modules` and `read_first` are JSONB arrays; `architecture_overview`,
`setup_instructions`, `data_flow` are freeform text generated from indexed repository context.

## Relationships

```
users 1──* code_repositories 1──* index_jobs
                            │
                            ├──* code_chunks        (ai-service owned)
                            ├──* indexed_files       (ai-service owned)
                            ├──* qa_history ──* users (also FKs the asking user)
                            ├──* pull_requests 1──* review_reports
                            └──* onboarding_docs
```

Every child table cascades on delete from its parent — disconnecting a repository (or deleting
a user) cleans up its entire index, history, and review data with no orphaned rows.

## Auth notes

`users.email_verified` is read by `UserPrincipal.isEnabled()`, which Spring Security's
`DaoAuthenticationProvider` checks *before* even comparing the password — an unverified account
gets rejected with a clear "please verify your email" error rather than a generic auth failure.
See [`AuthService`](../backend/src/main/java/com/codepilot/service/AuthService.java) for the
registration/verification/resend flow.
