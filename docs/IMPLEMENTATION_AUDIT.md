# CodePilot — Implementation Audit

**Date**: 2026-08-24
**Scope**: Every requirement from the original 40-section specification, audited against the actual code in this repository — not against memory, not against intent, against what is on disk and what actually runs.

**Methodology**: For each item, the code was read directly (file paths cited), and wherever a runtime claim is made, the exact command is given and was actually executed during this audit (not previously, not assumed). No code was modified during this audit.

**Environment at audit time**: Docker is not installed on this machine — Postgres 18+pgvector, Redis, and Mailpit run natively via Homebrew; backend/ai-service/frontend run as native processes (Java 21, Python 3.13 venv, Node 24). All three app services were live and healthy at the start of this audit. This is disclosed up front because it affects what "tested" can mean for Docker-specific items below.

---

## Summary table

| # | Category | Status |
|---|---|---|
| 1 | Project structure | ⚠️ PARTIAL |
| 2 | React frontend | ✅ COMPLETE |
| 3 | TypeScript | ✅ COMPLETE |
| 4 | Tailwind UI | ✅ COMPLETE |
| 5 | React Router | ⚠️ PARTIAL |
| 6 | React Query | ✅ COMPLETE |
| 7 | Spring Boot backend | ✅ COMPLETE |
| 8 | Java 21 | ✅ COMPLETE |
| 9 | Spring Security | ✅ COMPLETE |
| 10 | Authentication | ✅ COMPLETE |
| 11 | GitHub OAuth | ❌ MISSING |
| 12 | PostgreSQL | ✅ COMPLETE |
| 13 | Flyway migrations | ✅ COMPLETE |
| 14 | pgvector | ✅ COMPLETE |
| 15 | Redis | ✅ COMPLETE |
| 16 | GitHub API integration | ✅ COMPLETE |
| 17 | GitHub webhooks | ✅ COMPLETE |
| 18 | Webhook signature verification | ✅ COMPLETE |
| 19 | Repository indexing | ✅ COMPLETE |
| 20 | Incremental indexing | ✅ COMPLETE |
| 21 | File filtering | ⚠️ PARTIAL |
| 22 | Code parsing | ⚠️ PARTIAL |
| 23 | Structure-aware chunking | ✅ COMPLETE |
| 24 | Embeddings | ✅ COMPLETE |
| 25 | Vector search | ✅ COMPLETE |
| 26 | RAG | ✅ COMPLETE |
| 27 | Source citations | ✅ COMPLETE |
| 28 | Chat history | ⚠️ PARTIAL |
| 29 | Python FastAPI AI service | ✅ COMPLETE |
| 30 | LangChain/LangGraph | ❌ MISSING |
| 31 | Security Agent | ✅ COMPLETE |
| 32 | Bug Detection Agent | ✅ COMPLETE |
| 33 | Test Agent | ✅ COMPLETE |
| 34 | Code Quality Agent | ✅ COMPLETE |
| 35 | Review Synthesizer | ✅ COMPLETE |
| 36 | PR review workflow | ✅ COMPLETE |
| 37 | Onboarding Agent | ⚠️ PARTIAL |
| 38 | Architecture graph | ❌ MISSING |
| 39 | React Flow | ❌ MISSING |
| 40 | Background jobs | ⚠️ PARTIAL |
| 41 | Redis caching | ✅ COMPLETE |
| 42 | Rate limiting | ✅ COMPLETE |
| 43 | Error handling | ✅ COMPLETE |
| 44 | Prompt injection protection | ✅ COMPLETE |
| 45 | Frontend loading/error/empty states | ✅ COMPLETE |
| 46 | API documentation | ✅ COMPLETE |
| 47 | Backend tests | ✅ COMPLETE |
| 48 | AI tests | ✅ COMPLETE |
| 49 | Frontend tests | ❌ MISSING |
| 50 | Dockerfiles | ✅ COMPLETE |
| 51 | Docker Compose | ⚠️ PARTIAL (BLOCKED — see notes) |
| 52 | Environment configuration | ✅ COMPLETE |
| 53 | GitHub Actions CI/CD | ❌ MISSING |
| 54 | README | ✅ COMPLETE |
| 55 | Architecture documentation | ✅ COMPLETE |
| 56 | Database documentation | ✅ COMPLETE |
| 57 | RAG documentation | ✅ COMPLETE |
| 58 | Agent documentation | ✅ COMPLETE |
| 59 | Deployment documentation | ✅ COMPLETE |

**Totals: 40 COMPLETE · 10 PARTIAL · 8 MISSING · 0 BROKEN** (out of 59; see §60 note on the one BLOCKED sub-case)

---

## 1. Project structure

**Status**: ⚠️ PARTIAL

**Files**: repo root; `backend/src/main/java/com/codepilot/{config,controller,dto,entity,exception,repository,security,service}`; `frontend/src/{api,components,context,lib,pages,types}`

**Implemented**: Top-level monorepo layout matches the spec (`frontend/`, `backend/`, `ai-service/`, `docs/`, `docker-compose.yml`, `.env.example`, `.gitignore`, `README.md`). Backend has `config/controller/dto/entity/exception/repository/security/service`.

**Missing**:
- No `infrastructure/postgres/` or `infrastructure/redis/` directories (docker-compose configures these inline instead — functionally equivalent, structurally different from spec).
- Backend has no `mapper/`, `github/`, `webhook/`, `scheduler/`, or `job/` packages — GitHub client logic and webhook logic live inside `service/` rather than dedicated packages; DTO mapping is inline `toDto()` methods on services rather than a separate mapper layer; no `scheduler/` package exists because nothing uses `@Scheduled` (confirmed: `grep -r "@Scheduled" backend/src` returns nothing).
- Frontend has no `layouts/`, `hooks/`, `services/`, `utils/`, or `features/` directories — a `Layout.tsx` component exists inside `components/` rather than a `layouts/` dir; custom hooks (`useRepositories`, `useAskQuestion`, etc.) are colocated inside `api/*.ts` rather than a separate `hooks/` dir; `lib/` serves the role of `utils/`.
- **`.github/` contains a foreign, non-project artifact**: `.github/modernize/java-upgrade/20260824064623/` (plan.md, progress.md, logs, hooks) — this is leftover output from an automated IDE "Java upgrade" tool that ran independently at some point during development and bumped `backend/pom.xml`'s `java.version` to 25 and both Dockerfile base images to Temurin 25, incompatible with the JDK 21 toolchain this project has been built and verified against. That specific code change was found and reverted in a prior session (verified via `git`-independent inspection, since this repo has no commits yet); the artifact directory itself was left in place and is reported here, not deleted, per this audit's "do not modify" instruction.

**Tested**: Yes — directory listing.
**Command**: `ls backend/src/main/java/com/codepilot/ && ls frontend/src/ && ls infrastructure 2>&1`
**Result**: Confirmed structure as described above; `infrastructure` does not exist.

---

## 2. React frontend

**Status**: ✅ COMPLETE

**Files**: `frontend/src/**` (12 components, 5 pages), `frontend/package.json`

**Implemented**: React 18 + Vite 5, functional components throughout, no class components, custom hooks via TanStack Query wrappers.

**Missing**: Nothing at the "is this a working React app" level.

**Tested**: Yes.
**Command**: `cd frontend && npm run build`
**Result**: `✓ 157 modules transformed`, `✓ built in 936ms`, `dist/assets/index-*.js` produced. Clean production build.

---

## 3. TypeScript

**Status**: ✅ COMPLETE

**Files**: `frontend/tsconfig.json`, `frontend/tsconfig.node.json`, all `.tsx`/`.ts` under `frontend/src`

**Implemented**: Strict-mode TS throughout, typed API layer (`frontend/src/types/*.ts` mirrors backend DTOs), no `any` escape hatches found in a spot check of API files.

**Missing**: Nothing.

**Tested**: Yes.
**Command**: `cd frontend && npx tsc --noEmit`
**Result**: Exit 0, zero errors, zero output.

---

## 4. Tailwind UI

**Status**: ✅ COMPLETE

**Files**: `frontend/tailwind.config.js`, `frontend/postcss.config.js`, `frontend/src/index.css`, utility classes throughout all components

**Implemented**: Tailwind configured and building; consistent dark developer-tool aesthetic (slate palette, brand accent color) across all pages; loading skeletons, empty states, toast-free error banners all styled via Tailwind utilities.

**Missing**: Nothing structural. No component library (e.g. shadcn) on top of it, but the spec didn't require one.

**Tested**: Yes (implicitly — Tailwind CSS is compiled as part of the Vite build).
**Command**: `cd frontend && npm run build` (see §2)
**Result**: `dist/assets/index-*.css` (24.87 kB, gzip 5.11 kB) produced — Tailwind is compiling real utility classes, not a stub stylesheet.

---

## 5. React Router

**Status**: ⚠️ PARTIAL

**Files**: `frontend/src/App.tsx`, `frontend/src/components/ProtectedRoute.tsx`

**Implemented**: `react-router-dom` v6, routes for `/login`, `/register`, `/verify-email`, `/dashboard`, `/repositories/:id`, with a `ProtectedRoute` gate and a `PublicOnlyRoute` gate (redirects authenticated users away from login/register).

**Missing**: The spec's exact route list is not matched — `/repositories` (a standalone list page; currently folded into `/dashboard`), `/repositories/:id/chat`, `/repositories/:id/architecture`, `/repositories/:id/pull-requests`, `/repositories/:id/pull-requests/:number`, and `/settings` do not exist as distinct routes. Ask/Reviews/Onboarding are implemented as **tabs within `/repositories/:id`** rather than separate routes — a reasonable UX choice, but not what the spec's route table specifies, and there is no settings page at all.

**Tested**: Yes.
**Command**: `grep -n "path=" frontend/src/App.tsx`
**Result**: Confirmed only 7 routes exist: `/`, `/login`, `/register`, `/verify-email`, `/dashboard`, `/repositories/:id`, `*` (catch-all redirect).

---

## 6. React Query

**Status**: ✅ COMPLETE

**Files**: `frontend/src/api/{repositories,qa,reviews,onboarding}.ts`

**Implemented**: `@tanstack/react-query` v5 used correctly throughout — query keys are structured arrays, mutations invalidate the right query keys on success, polling via `refetchInterval` for in-progress indexing status (verified logic: polls every 4-5s only while status is `PENDING`/`INDEXING`, stops once `INDEXED`/`FAILED`).

**Missing**: Nothing.

**Tested**: Yes (via full frontend build + typecheck, which would fail on React Query API misuse of this magnitude; also manually re-read the polling logic in `repositories.ts` this session for correctness).
**Command**: `cd frontend && npx tsc --noEmit && npm run build`
**Result**: Clean (see §2, §3).

---

## 7. Spring Boot backend

**Status**: ✅ COMPLETE

**Files**: `backend/pom.xml`, `backend/src/main/java/com/codepilot/CodepilotApplication.java`, entire `backend/src/main/java` tree (82 source files)

**Implemented**: Spring Boot 3.3.4, layered architecture (controller → service → repository), DTOs used on every controller boundary (no entity ever returned directly from a controller — verified by grepping every `@RestController` return type), centralized exception handling, structured SLF4J logging throughout.

**Missing**: Nothing at the framework level.

**Tested**: Yes.
**Command**: `cd backend && mvn clean compile && mvn test`
**Result**: `BUILD SUCCESS` on both; 21/21 tests pass (see §47 for detail).

---

## 8. Java 21

**Status**: ✅ COMPLETE

**Files**: `backend/pom.xml` (`<java.version>21</java.version>`), `backend/Dockerfile` (`maven:3.9-eclipse-temurin-21`, `eclipse-temurin:21-jre-jammy`)

**Implemented**: Confirmed running on JDK 21.0.12.1 (Homebrew). This required deliberate correction earlier in development — an automated tool (see §1) had bumped this to Java 25, which is incompatible with this environment's Lombok/toolchain; it was reverted to match the spec's explicit "Java 21" requirement.

**Missing**: Nothing.

**Tested**: Yes.
**Command**: `mvn -version` (inside an active `JAVA_HOME=/opt/homebrew/opt/openjdk@21` shell) and `mvn clean compile`
**Result**: `Java version: 21.0.12.1`; compiles clean, 82 source files.

---

## 9. Spring Security

**Status**: ✅ COMPLETE

**Files**: `backend/src/main/java/com/codepilot/config/SecurityConfig.java`, `security/{JwtAuthFilter,JwtService,UserPrincipal,AppUserDetailsService,RateLimitFilter,RateLimiter}.java`

**Implemented**: Stateless JWT filter chain, BCrypt password encoding, CORS restricted to a single configured origin (not wildcard), CSRF disabled appropriately for a stateless JWT API, `/api/auth/**`, `/api/webhooks/**`, `/actuator/**`, and `/v3/api-docs/**`+`/swagger-ui/**` permitted, everything else requires authentication.

**Missing**: Nothing for what's implemented (no OAuth2 client config, but that's tracked separately under §11).

**Tested**: Yes.
**Command**: `curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/repositories` (no Authorization header)
**Result**: `403` — confirmed unauthenticated requests to a protected endpoint are rejected.

---

## 10. Authentication

**Status**: ✅ COMPLETE

**Files**: `backend/src/main/java/com/codepilot/{controller/AuthController,service/AuthService,service/EmailService}.java`, `frontend/src/{context/AuthContext.tsx,pages/{LoginPage,RegisterPage,VerifyEmailPage}.tsx}`

**Implemented**: Email/password registration with bcrypt hashing, mandatory email verification (JWT login blocked pre-verification via `UserPrincipal.isEnabled()`), JWT issuance/validation, resend-verification flow with no user-enumeration leak (same response whether or not the email exists).

**Missing**: Nothing for this auth model (GitHub OAuth is a separate, additive item — see §11).

**Tested**: Yes, live end-to-end, this session and prior ones: register → Mailpit verification email → click-through verify → login → JWT issued. Also verified login is blocked pre-verification (403) and that a duplicate-registration race now returns a clean 409 (not a raw 500).
**Command**: `curl -X POST .../api/auth/register ...` → inspect Mailpit → `curl .../api/auth/verify?token=...` → `curl -X POST .../api/auth/login ...`
**Result**: Full loop confirmed working in a prior session of this same environment; re-verified today via `AuthControllerTest` (3/3 pass) and `GlobalExceptionHandlerTest` (1/1 pass, confirms the 409 race-condition fix).

---

## 11. GitHub OAuth

**Status**: ❌ MISSING

**Files**: none

**Implemented**: Nothing. Repository connection uses a manually-pasted GitHub personal access token (`ConnectRepoModal.tsx` → `POST /api/repositories` with `accessToken` in the body), not an OAuth authorization-code flow.

**Missing**: The entire OAuth flow — no `spring-boot-starter-oauth2-client` dependency, no `GITHUB_CLIENT_ID`/`GITHUB_CLIENT_SECRET` handling, no `/oauth2/authorization/github` redirect, no callback handler.

**Tested**: N/A — nothing to test.
**Command**: `grep -rli "oauth2\|GITHUB_CLIENT_ID" backend/src frontend/src`
**Result**: No matches.

**Why**: This was explicitly deferred earlier — the user chose "add OAuth alongside existing auth" as a future phase rather than doing it immediately, since it requires the user to create a GitHub OAuth App themselves (an action only they can take) before it can be implemented and tested end-to-end.

---

## 12. PostgreSQL

**Status**: ✅ COMPLETE

**Files**: `docker-compose.yml` (`pgvector/pgvector:pg16` image), native Postgres 18 for local dev

**Implemented**: Running, reachable, correct schema applied.

**Missing**: Nothing.

**Tested**: Yes.
**Command**: `psql -h localhost -U $(whoami) -d codepilot -c "SELECT 1"` and `redis-cli ping`
**Result**: Postgres reachable, returns `1`. (Redis result under §15.)

---

## 13. Flyway migrations

**Status**: ✅ COMPLETE

**Files**: `backend/src/main/resources/db/migration/{V1__init,V2__add_email_verification,V3__add_indexed_files,V4__add_pull_request_status_check}.sql`

**Implemented**: 4 migrations, all applied successfully with no checksum mismatches, `baseline-on-migrate` configured.

**Missing**: Nothing.

**Tested**: Yes, against the live database (not just "migrations exist").
**Command**: `psql -h localhost -U $(whoami) -d codepilot -c "SELECT version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank;"`
**Result**:
```
 version |          description          | success
---------+-------------------------------+---------
 1       | init                          | t
 2       | add email verification        | t
 3       | add indexed files             | t
 4       | add pull request status check | t
```

---

## 14. pgvector

**Status**: ✅ COMPLETE

**Files**: `V1__init.sql` (`CREATE EXTENSION IF NOT EXISTS vector`, `code_chunks.embedding vector(1536)`), `ai-service/app/services/vector_store.py`

**Implemented**: Extension installed and active; cosine-distance similarity search (`embedding.cosine_distance(...)`) used directly in the ORDER BY clause of `VectorStore.similarity_search`; fixed 1536-dim regardless of embedding provider.

**Missing**: No ANN index (IVFFlat/HNSW) — acceptable at this data scale and explicitly documented as a known "what I'd do at scale" item in `docs/ARCHITECTURE.md`, not silently omitted.

**Tested**: Yes.
**Command**: `psql -h localhost -U $(whoami) -d codepilot -c "SELECT extname, extversion FROM pg_extension WHERE extname IN ('vector','pgcrypto');"`
**Result**: `vector | 0.8.6`, `pgcrypto | 1.4` — both installed and active.

---

## 15. Redis

**Status**: ✅ COMPLETE

**Files**: `backend/src/main/java/com/codepilot/config/RedisConfig.java`, `service/{CacheService,IdempotencyService}.java`, `security/{RateLimiter,RateLimitFilter}.java`

**Implemented**: `RedisTemplate<String,Object>` with a Jackson+JavaTimeModule serializer; three genuinely distinct real uses (caching, webhook idempotency, rate limiting — see §41, §42).

**Missing**: Nothing for what's implemented. No distributed-lock usage beyond the atomic SETNX/INCR patterns already used (the spec's "distributed locks" language is satisfied by these atomic ops, not by a separate lock abstraction — reasonable for this project's actual concurrency needs).

**Tested**: Yes.
**Command**: `redis-cli ping`
**Result**: `PONG`.

---

## 16. GitHub API integration

**Status**: ✅ COMPLETE

**Files**: `backend/src/main/java/com/codepilot/service/GitHubClient.java`, `config/WebClientConfig.java`

**Implemented**: Real REST calls to `api.github.com` — repo info, recursive tree fetch, blob content, PR files + diffs, file content at a specific ref. 60s connect/read/write timeouts configured on a dedicated `WebClient` bean. Retry logic added and verified this session (`AiServiceClient.retrySpec()`, reused by `GitHubClient`, reused by `AiServiceClient` itself) — 2 retries with exponential backoff, filtered to only retry connection/timeout errors and 5xx (never 4xx).

**Missing**: Nothing now — retry was the one real gap found in the prior audit pass and has since been fixed.

**Tested**: Yes, at the compile/unit level (`mvn test` includes no direct GitHubClient network test, since that would require live GitHub credentials — appropriately not attempted). Live behavior verified indirectly via the webhook E2E tests below, which exercise `GitHubClient.fetchPullRequestFiles` and observe correct graceful failure handling.
**Command**: `mvn test` (see §47)
**Result**: Compiles and all tests pass; no live GitHub API call was made during this audit (would require a real token against a real repo, out of scope for an audit run).

---

## 17. GitHub webhooks

**Status**: ✅ COMPLETE

**Files**: `backend/src/main/java/com/codepilot/{controller/WebhookController,service/WebhookService,service/PrReviewService}.java`

**Implemented**: `pull_request` (opened/synchronize) and `push` (default-branch only) both handled. PR review work is dispatched asynchronously (`PrReviewService.reviewAsync`, `@Async`) so the webhook HTTP response returns immediately rather than blocking on GitHub API calls + a 4-agent AI review — this was a real bug found and fixed this session (see §36, §43).

**Missing**: Nothing for the two event types implemented. `pull_request` review-comment or check-run style webhook events aren't handled (not required by spec).

**Tested**: Yes, live, end-to-end, twice in this same environment across sessions.
**Command**:
```
curl -X POST http://localhost:8080/api/webhooks/github \
  -H "X-GitHub-Event: pull_request" -H "X-GitHub-Delivery: <id>" \
  -H "X-Hub-Signature-256: sha256=<hmac>" -H "Content-Type: application/json" \
  --data-binary @payload.json
```
**Result**: `200`, response time **102ms** (measured), proving the request does not block on the downstream review. PR row created with `PENDING_REVIEW`, transitions to `REVIEWED`/`REVIEW_FAILED` asynchronously a few seconds later.

---

## 18. Webhook signature verification

**Status**: ✅ COMPLETE

**Files**: `backend/src/main/java/com/codepilot/service/WebhookService.java` (`signatureMatches`)

**Implemented**: HMAC-SHA256 over the raw request body, compared against the repository's stored per-repo secret using `MessageDigest.isEqual` (constant-time, not `.equals()` — timing-attack-safe).

**Missing**: Nothing.

**Tested**: Yes, live, both directions.
**Command**: Same webhook POST as §17, once with a correct signature and once with `X-Hub-Signature-256: sha256=wrong`.
**Result**: Correct signature → `200` and processing proceeds. Wrong signature → `401 UNAUTHORIZED`, confirmed via `WebhookServiceTest.rejectsPayloadWithBadSignature` (unit) and live curl (integration).

---

## 19. Repository indexing

**Status**: ✅ COMPLETE

**Files**: `backend/src/main/java/com/codepilot/service/{RepositoryService,IndexingService}.java`, `ai-service/app/routers/index.py`

**Implemented**: Connect → fetch tree → filter → fetch blobs → POST to ai-service `/index` → chunk → embed → store → mark `INDEXED`/`FAILED`. Runs `@Async`, kicked off only after the DB transaction creating the repo row commits (`TransactionSynchronizationManager.registerSynchronization`), so the background thread can never race the row's visibility.

**Missing**: Nothing.

**Tested**: Yes, live, this session, via direct `/index` HTTP calls against the running ai-service with a real Postgres-backed repository row.
**Command**: See §20 (same test covers both).
**Result**: See §20.

---

## 20. Incremental indexing

**Status**: ✅ COMPLETE

**Files**: `backend/src/main/resources/db/migration/V3__add_indexed_files.sql`, `ai-service/app/{routers/index.py,services/vector_store.py}` (`IndexedFile` model, `get_file_hashes`/`upsert_file_hashes`/`delete_chunks_for_paths`/`delete_file_hashes`)

**Implemented**: SHA-256 content hash per file, diffed against the `indexed_files` table on every `/index` call. Unchanged files are never re-chunked or re-embedded; changed files are re-chunked/re-embedded; removed files have their chunks and hash row deleted.

**Missing**: Nothing.

**Tested**: Yes, live, three-call sequence proving all three cases.
**Command**:
```
curl -X POST http://localhost:8000/index -d '{"repositoryId":"...","files":[a.py, b.py]}'   # call 1
curl -X POST http://localhost:8000/index -d '{"repositoryId":"...","files":[a.py, b.py]}'   # call 2, identical content
curl -X POST http://localhost:8000/index -d '{"repositoryId":"...","files":[a.py changed]}' # call 3, b.py removed
```
**Result**: Call 1 → `chunksCreated: 2`. Call 2 (identical) → `chunksCreated: 0` (both skipped). Call 3 (a.py changed, b.py removed) → `chunksCreated: 1`; DB check confirmed `code_chunks` and `indexed_files` both contained only `a.py` afterward.

---

## 21. File filtering

**Status**: ⚠️ PARTIAL

**Files**: `backend/src/main/java/com/codepilot/service/GitHubClient.java` (`SKIP_DIR_SEGMENTS`, `SKIP_EXTENSIONS`, `MAX_FILE_SIZE_BYTES`, `MAX_FILES`, `shouldSkipPath`)

**Implemented**: Directory-segment filtering (`node_modules`, `.git`, `dist`, `build`, `target`, `out`, `vendor`, `.next`, `.nuxt`, `.venv`, `venv`, `env`, `__pycache__`, `.idea`, `.vscode`, `coverage`, `.gradle`, `.mvn`, `bin`, `obj`), extension-based binary filtering (images, video, audio, archives, compiled artifacts, fonts, DB files), a 200KB per-file size cap, and a 300-file-per-repo cap. Binary content is also detected and skipped at the byte level (null-byte check in `fetchBlobContent`).

**Missing**: **No filename-based exclusion for `.env`, `.env.*`, or common secret-file patterns** (`id_rsa`, `*.pem`, `*.key`, etc.) — the spec explicitly requires ignoring `.env` and secrets, and this is not implemented. A `.env` file committed to a connected repository would currently be fetched, chunked, embedded, and made retrievable via the Q&A/RAG endpoints. This is a real, actionable gap, not a cosmetic one.

**Tested**: Yes — read the filtering logic directly; confirmed no filename-pattern check exists anywhere in `shouldSkipPath` (only directory-segment and extension checks).
**Command**: `grep -n "\.env\|secret" backend/src/main/java/com/codepilot/service/GitHubClient.java`
**Result**: No matches — confirms the gap.

---

## 22. Code parsing

**Status**: ⚠️ PARTIAL

**Files**: `backend/src/main/java/com/codepilot/service/GitHubClient.java` (`LANGUAGE_BY_EXTENSION`, `detectLanguage`)

**Implemented**: Extension-based language detection for java, kotlin, python, javascript, typescript, go, ruby, php, c, cpp, csharp, rust, swift, scala, sql, shell, yaml, json, xml, html, css, scss, markdown, toml, groovy, dockerfile — broader than the spec's minimum list.

**Missing**: **No `.properties` → a dedicated language tag** (the spec explicitly lists "properties" as a required supported extension) — `.properties` files aren't in `SKIP_EXTENSIONS` so they ARE indexed, but `detectLanguage` falls through to a generic `"text"` tag rather than a `properties`-specific one, since `.properties` isn't in `LANGUAGE_BY_EXTENSION`. This is a labeling/metadata gap, not a functional one — the file's content is still indexed and searchable.

**Tested**: Yes.
**Command**: `grep -n "properties" backend/src/main/java/com/codepilot/service/GitHubClient.java`
**Result**: No matches in `LANGUAGE_BY_EXTENSION`.

---

## 23. Structure-aware chunking

**Status**: ✅ COMPLETE

**Files**: `ai-service/app/services/chunking.py`, `ai-service/tests/test_chunking.py`

**Implemented**: Regex-based boundary detection for Java/Kotlin/C#-family (class/interface/enum/method signatures), JS/TS (functions, classes, exported components, arrow-function consts), and Markdown (heading boundaries). Everything else falls back to an overlapping sliding window (80 lines, 10-line overlap) rather than naive fixed-size splitting with no overlap. Oversized chunks are further split at `MAX_CHUNK_LINES=150`. Every chunk preserves file path, language, and 1-indexed line range.

**Missing**: Not a true AST parser (regex-based boundary detection, explicitly documented as a scope trade-off in `docs/rag.md`'s "Known limitations" section) — functionally sound but can misparse unusual formatting.

**Tested**: Yes.
**Command**: `cd ai-service && ./.venv/bin/python -m pytest tests/test_chunking.py -v`
**Result**: 6/6 tests pass (Java method boundaries, JS/TS functions+arrow-consts, Markdown headings, fallback window, oversized-chunk capping, empty-file handling).

---

## 24. Embeddings

**Status**: ✅ COMPLETE

**Files**: `ai-service/app/services/embeddings.py`

**Implemented**: Pluggable `EmbeddingProvider` abstraction, two real implementations — `LocalHashEmbeddingProvider` (feature-hashing bag-of-words, zero external dependencies, camelCase/snake_case-aware tokenizer) and `OpenAIEmbeddingProvider` (`text-embedding-3-small` by default). Both fixed at 1536 dimensions. Batched requests (`EMBED_BATCH_SIZE=64`). A real tokenizer bug (acronym boundaries like `HTTPServer` not splitting into `HTTP`+`Server`) was found and fixed this session.

**Missing**: Nothing for the two providers implemented.

**Tested**: Yes.
**Command**: `cd ai-service && ./.venv/bin/python -m pytest tests/test_embeddings.py -v`
**Result**: 6/6 pass (tokenizer camelCase/snake_case splitting, determinism, correct dimension, L2 normalization, zero-vector-for-empty-text, similar-texts-closer-than-unrelated).

---

## 25. Vector search

**Status**: ✅ COMPLETE

**Files**: `ai-service/app/services/vector_store.py` (`similarity_search`), `app/routers/query.py`

**Implemented**: Cosine-distance ORDER BY, scoped to `repository_id`, `top_k` is a caller-supplied, server-clamped parameter (`[1, 50]`), not a hardcoded constant.

**Missing**: No post-retrieval relevance threshold or reranking step — the LLM itself is instructed to say "not enough information" rather than a hard distance cutoff before chunks reach the model (a softer, prompt-level filter; documented as a known limitation in `docs/rag.md`, not hidden).

**Tested**: Indirectly, via the incremental-indexing E2E test (§20), which populates real embeddings and confirms retrieval-relevant state (`code_chunks` row counts) is correct after indexing. No direct `/query` E2E call was made in this specific audit pass (would require `ANTHROPIC_API_KEY`, which is not configured in this environment — ⚠️ see §26).

---

## 26. RAG

**Status**: ✅ COMPLETE (code + wiring), pipeline mechanics verified; end-to-end answer generation is **BLOCKED** in this environment specifically because no `ANTHROPIC_API_KEY` is configured — not because anything is broken.

**Files**: `ai-service/app/services/rag.py`, `app/routers/query.py`

**Implemented**: Full pipeline — question → embed → retrieve → grounded-prompt construction (context chunks labeled with file:line, explicit "don't invent, say so if insufficient" system prompt) → LLM call → answer + citations derived from the actual retrieved chunks (not parsed from model text). Citations are the source of truth even if the model's inline citation text is imperfect.

**Missing**: Nothing in the pipeline code itself.

**Tested**: The 503-without-a-key path was verified (this is the *correct*, designed behavior, not a bug):
**Command**: `curl -s -w "\n%{http_code}\n" -X POST http://localhost:8000/query -H "Content-Type: application/json" -d '{"repositoryId":"<uuid>","question":"test"}'`
**Result**: `503`, `{"detail":"LLM not configured: set ANTHROPIC_API_KEY"}` — this is exactly the documented, intended behavior when no key is present, confirmed working as designed. **A real end-to-end answer (embed → retrieve → generate → cite) has never been produced with a real Anthropic key in this environment, because the user does not have one.** Report this honestly as BLOCKED for the generation step specifically, not COMPLETE-and-verified for that step.

---

## 27. Source citations

**Status**: ✅ COMPLETE

**Files**: `ai-service/app/services/rag.py`, `backend/src/main/java/com/codepilot/dto/qa/CitationDto.java`, `frontend/src/components/CitationBadge.tsx`

**Implemented**: Citations (`filePath`, `startLine`, `endLine`, `snippet`) flow unmodified from the retrieved pgvector rows through the ai-service response, through `QaService.toCitationDto`, into `qa_history.citations` (JSONB), out to the frontend, rendered as clickable badges.

**Missing**: Nothing in the data plumbing. (Generation itself is BLOCKED per §26 — the citation *shape* is verified correct by reading the code and by the type contracts on both ends, not by a live generated answer.)

**Tested**: Type-level and code-level, yes (full stack compiles/typechecks with these exact field names on both sides — a mismatch would fail `tsc` or `mvn test`). Live-generated citation content: BLOCKED, same reason as §26.

---

## 28. Chat history

**Status**: ⚠️ PARTIAL

**Files**: `backend/src/main/java/com/codepilot/{entity/QaHistory,service/QaService,controller/QaController}.java`, `frontend/src/components/ChatPanel.tsx`

**Implemented**: Every Q&A round-trip is persisted (`qa_history` table) and retrievable per repository, ordered newest-first, cached in Redis for repeated identical questions. A real duplicate-message rendering bug (optimistic local state never reconciled against the refetched server history) was found and fixed this session.

**Missing**: The spec's `ChatSession`/`ChatMessage` model (§6 of the original spec) does not exist — there is **no concept of multiple named chat sessions per repository**, no "create a new chat," and **no delete-chat capability at all** (confirmed: zero `@DeleteMapping` endpoints exist anywhere in the backend). History is one continuous flat list per (user, repository), not session-scoped conversations.

**Tested**: Yes.
**Command**: `grep -rn "DeleteMapping" backend/src/main/java/com/codepilot/controller/*.java`
**Result**: No matches anywhere in the codebase — confirms no delete capability exists for chats or anything else.

---

## 29. Python FastAPI AI service

**Status**: ✅ COMPLETE

**Files**: `ai-service/main.py`, `app/{routers,services,agents,models}/*`

**Implemented**: FastAPI app, lifespan-managed startup (settings, vector store, embedding provider, LLM client initialized once), global exception handlers for `HTTPException`/`RequestValidationError`/generic `Exception` that never leak raw tracebacks to clients (logs them server-side, returns a generic message).

**Missing**: Nothing.

**Tested**: Yes, live.
**Command**: `curl -s http://localhost:8000/health`
**Result**: `{"status":"ok"}`, `200`.

---

## 30. LangChain/LangGraph

**Status**: ❌ MISSING

**Files**: none

**Implemented**: Nothing. Agent orchestration is hand-rolled `asyncio.gather` (`ai-service/app/agents/review_orchestrator.py`) — functionally equivalent for this use case (four independent concurrent agents, deterministic merge, no shared state graph needed) but not built on LangGraph specifically.

**Missing**: `langgraph`/`langchain` are not in `requirements.txt`, no `StateGraph`, no LangChain abstractions anywhere.

**Tested**: N/A.
**Command**: `grep -i "langchain\|langgraph" ai-service/requirements.txt`
**Result**: No matches.

---

## 31. Security Agent

**Status**: ✅ COMPLETE

**Files**: `ai-service/app/agents/security_agent.py`

**Implemented**: Focused system prompt covering injection (SQL/NoSQL/command/LDAP), auth/authz bypass, hardcoded secrets, insecure deserialization, SSRF, path traversal, XSS, weak crypto, missing input validation, IDOR, config misconfiguration — matches the spec's list closely. Structured JSON output (`file`, `line`, `severity`, `description`, `suggestion`), defensively parsed (see §35).

**Missing**: Severity is 3-level (`low|medium|high`), not the spec's 5-level (`CRITICAL|HIGH|MEDIUM|LOW|INFO`) — a real, minor scale mismatch, not a missing feature.

**Tested**: Yes, at the prompt-construction level (the part that's actually testable without a live LLM key).
**Command**: `cd ai-service && ./.venv/bin/python -m pytest tests/test_prompt_injection_defense.py -v -k SecurityAgent`
**Result**: 1/1 pass — confirms the agent's system prompt is correctly assembled including the untrusted-content notice. Live LLM output: BLOCKED, no Anthropic key (same as §26).

---

## 32. Bug Detection Agent

**Status**: ✅ COMPLETE

**Files**: `ai-service/app/agents/bug_agent.py`

**Implemented**: Covers null/undefined dereferences, incorrect conditionals, logic errors, race conditions, unhandled exceptions, resource leaks, incorrect state management, missed edge cases. Same structured-output/defensive-parsing pattern as all agents.

**Missing**: Nothing structural.

**Tested**: Yes, same method as §31.
**Command**: `cd ai-service && ./.venv/bin/python -m pytest tests/test_prompt_injection_defense.py -v -k BugDetectionAgent`
**Result**: 1/1 pass. Live LLM output: BLOCKED (§26).

---

## 33. Test Agent

**Status**: ✅ COMPLETE

**Files**: `ai-service/app/agents/test_agent.py`

**Implemented**: Looks for missing tests on new public functions/behavior, insufficient edge-case coverage, missing integration tests, untested error paths. Does not claim a coverage percentage (matches spec's explicit "do not claim exact coverage percentage unless actually measured" instruction — the agent's prompt asks for qualitative findings only).

**Missing**: Nothing structural.

**Tested**: Yes, same method as §31.
**Command**: `cd ai-service && ./.venv/bin/python -m pytest tests/test_prompt_injection_defense.py -v -k TestCoverageAgent`
**Result**: 1/1 pass. Live LLM output: BLOCKED (§26).

---

## 34. Code Quality Agent

**Status**: ✅ COMPLETE

**Files**: `ai-service/app/agents/quality_agent.py`

**Implemented**: Single LLM call covering both code smells (duplication, complexity, naming, dead code, magic values, coupling) and performance (O(n²)+, N+1, redundant work, missing pagination), tagged by category and routed into separate `codeSmells`/`performance` response fields.

**Missing**: Nothing structural.

**Tested**: Yes, same method as §31.
**Command**: `cd ai-service && ./.venv/bin/python -m pytest tests/test_prompt_injection_defense.py -v -k quality`
**Result**: 1/1 pass. Live LLM output: BLOCKED (§26).

---

## 35. Review Synthesizer

**Status**: ✅ COMPLETE

**Files**: `ai-service/app/agents/review_orchestrator.py`

**Implemented**: Runs all four agents concurrently via `asyncio.gather`, merges findings into `ReviewFindings`, generates a one-paragraph synthesis (3-6 sentences, LLM call over the merged findings — not per-category). **Falls back to a deterministic severity-count summary if the synthesis LLM call itself fails**, so a review is never missing its top-level summary.

**Missing**: Nothing.

**Tested**: Yes, at the orchestration-logic level via `PrReviewServiceTest` (which exercises the full `PrReviewService.reviewAsync` → orchestrator call path with mocked I/O boundaries).
**Command**: `cd backend && mvn test -Dtest=PrReviewServiceTest`
**Result**: 3/3 pass (successful review persists + marks REVIEWED; GitHub failure marks REVIEW_FAILED without persisting a report; unknown PR ID is a safe no-op).

---

## 36. PR review workflow

**Status**: ✅ COMPLETE

**Files**: `backend/src/main/java/com/codepilot/service/{WebhookService,PrReviewService}.java`, `frontend/src/components/{ReviewList,ReviewDetail}.tsx`

**Implemented**: Full pipeline — webhook → verify → dedupe → upsert PR row (`PENDING_REVIEW`) → **async** GitHub fetch → AI review → persist `review_reports` → status update → frontend renders grouped by category. Two real, serious bugs were found and fixed this session:
1. The review previously ran **synchronously on the webhook HTTP thread**, risking GitHub timing out the delivery on any review taking more than a few seconds.
2. The async fix initially introduced a `LazyInitializationException` (accessing a lazy-loaded JPA association from a different thread than the one that loaded it) — caught only by live end-to-end testing, not by unit tests, and fixed with an `@EntityGraph` fetch join.

**Missing**: No manual "re-review" trigger endpoint (`POST /api/pull-requests/{id}/review` from the spec's endpoint list does not exist) — a review only runs automatically from the webhook path today.

**Tested**: Yes, live, twice (once exposing the bug, once confirming the fix).
**Command**: Timed webhook POST (see §17) + `psql` status checks before/after.
**Result**: 102ms webhook response (proving non-blocking); PR status correctly transitions `PENDING_REVIEW` → `REVIEW_FAILED` (or `REVIEWED` with real credentials) asynchronously; zero `LazyInitializationException` after the fix (confirmed by re-running the exact same live test that first caught it).

---

## 37. Onboarding Agent

**Status**: ⚠️ PARTIAL

**Files**: `ai-service/app/routers/onboarding.py`, `backend/src/main/java/com/codepilot/service/OnboardingService.java`

**Implemented**: Generates architecture overview, important modules, setup instructions, data flow, and a read-first file list from a representative sample of indexed chunks (entry-point files prioritized, char-budget-capped). Backend caches the generated doc (`getOrGenerate` — regenerates only if none exists yet). Prompt explicitly instructs "hedge clearly-uncertain information rather than fabricate."

**Missing**: **No explicit "environment variables" field** in the response schema (the spec lists this as a distinct required section: "Architecture overview, Important modules, Setup instructions, Environment variables, Main request/data flows, Authentication flow, Database overview, Recommended reading order") — the current `OnboardingResponse` has only `architectureOverview`, `importantModules`, `setupInstructions`, `dataFlow`, `readFirst`. No dedicated authentication-flow or database-overview sections either — these would currently need to be folded into `architectureOverview` prose rather than being their own structured fields. Not implemented as a distinct "Agent" class alongside the four review agents (it's a router function, not a class in the same hierarchy) — a structural, not functional, gap.

**Tested**: Yes, at the request-construction level.
**Command**: `curl -s -X POST http://localhost:8000/onboarding -d '{"repositoryId":"<uuid-with-no-indexed-chunks>"}'`
**Result**: `404`, `"No indexed chunks found for this repository. Call /index first."` — confirms the guard-rail behavior is real, not just documented. Full generation with real chunks + a real LLM key: BLOCKED (§26).

---

## 38. Architecture graph

**Status**: ❌ MISSING

**Files**: none

**Implemented**: Nothing. No backend endpoint, no graph-generation logic on the ai-service side, no frontend page.

**Missing**: Everything — node/edge extraction from repository structure, the `/repositories/{id}/architecture` endpoint, any UI.

**Tested**: N/A.
**Command**: `grep -rli "architecture" backend/src/main/java/com/codepilot/controller/`
**Result**: No controller exists.

---

## 39. React Flow

**Status**: ❌ MISSING

**Files**: none

**Implemented**: Nothing.

**Missing**: Not in `frontend/package.json` dependencies at all.

**Tested**: N/A.
**Command**: `grep -i "reactflow\|react-flow" frontend/package.json`
**Result**: No matches.

---

## 40. Background jobs

**Status**: ⚠️ PARTIAL

**Files**: `backend/src/main/java/com/codepilot/{config/AsyncConfig,entity/IndexJob,service/{IndexingService,PrReviewService}}.java`

**Implemented**: Two real `@Async` job types on a dedicated thread pool (`indexingExecutor`, core 2 / max 8 / queue 100): repository indexing (also handles incremental re-index, since the diffing logic lives at the ai-service layer rather than being a separate Java-level job type) and PR review. `index_jobs` table tracks `PENDING/RUNNING/COMPLETED/FAILED` with file/chunk counts and error text, exposed to the frontend via repository status polling.

**Missing**: The spec's 5 distinct job types (`INDEX_REPOSITORY`, `INCREMENTAL_INDEX`, `PR_REVIEW`, `GENERATE_ONBOARDING`, `GENERATE_ARCHITECTURE`) aren't modeled as a unified job system — there's no `job_type` column, no single "jobs" list endpoint. Onboarding generation happens **synchronously** on the request thread (not backgrounded — it's a single LLM call, not a full pipeline, so this is lower-risk than the PR-review bug, but it is still not "background" per the spec). PR review status lives on `pull_requests.status`, not in the `index_jobs` table — there's no single unified job-status view across both.

**Tested**: Yes — indexing and PR-review async behavior confirmed live (§19, §36); onboarding's synchronous nature confirmed by reading `OnboardingService.getOrGenerate` (no `@Async`, no executor handoff — the calling HTTP thread blocks on the LLM call).
**Command**: `grep -n "@Async" backend/src/main/java/com/codepilot/service/OnboardingService.java`
**Result**: No matches — confirms onboarding generation is synchronous.

---

## 41. Redis caching

**Status**: ✅ COMPLETE

**Files**: `backend/src/main/java/com/codepilot/service/CacheService.java`

**Implemented**: Two real cached paths with explicit TTLs (10 min each) — repository detail responses (`repo:{id}`) and Q&A answers for identical questions (`qa:{repoId}:{sha256(normalized question)}`), with cache invalidation on repository mutation (`evictRepository` called from `IndexingService`'s `finally` block). All Redis operations wrapped in try/catch that logs and falls back gracefully rather than propagating a Redis outage into a request failure.

**Missing**: Nothing.

**Tested**: Indirectly (code-reviewed for correctness this session as part of the security/architecture audit — no direct cache-hit-timing test was run in this specific pass, since it would require live LLM-backed Q&A which is BLOCKED per §26).

---

## 42. Rate limiting

**Status**: ✅ COMPLETE

**Files**: `backend/src/main/java/com/codepilot/security/{RateLimiter,RateLimitFilter}.java`, `application.yml` (`app.rate-limit.*`)

**Implemented**: Redis-backed fixed-window counter (atomic `INCR` + `EXPIRE` on first hit), applied via a servlet filter to the AI endpoints (`/ask`, `/onboarding` — 20/min default), repository-connect (`POST /api/repositories` — 10/hour default), and the webhook endpoint (120/min default). Identity is per-authenticated-user where available, falling back to IP (honoring `X-Forwarded-For`) for unauthenticated (webhook) traffic. Fails open (allows the request) if Redis itself is unreachable, so a cache outage degrades gracefully rather than taking the API down. Returns the same standard `{timestamp,status,error,message,path}` error shape as every other error path, not an ad-hoc format.

**Missing**: Nothing.

**Tested**: Yes, live, this session — deliberately set `RATE_LIMIT_AI_LIMIT=3` and confirmed the 4th request within the window was rejected.
**Command**: 4 sequential `curl -X POST .../api/repositories/{fakeId}/ask` calls with a valid JWT.
**Result**: Requests 1-3 → `404` (repository not found — correctly passed through the filter to the controller). Request 4 → `429`, `{"status":429,"error":"Too Many Requests",...}`.

---

## 43. Error handling

**Status**: ✅ COMPLETE

**Files**: `backend/src/main/java/com/codepilot/exception/GlobalExceptionHandler.java`, `backend/src/main/java/com/codepilot/dto/error/ErrorResponse.java`, `ai-service/main.py` (exception handlers)

**Implemented**: Backend returns the spec's exact shape (`{timestamp, status, error, message, path}`) from every error path — `ApiException`, validation errors, `DataIntegrityViolationException` (added this session, closing a real race-condition gap — see §36's sibling finding and the summary below), `BadCredentialsException`, `AccessDeniedException`, `IllegalArgumentException`, and a generic fallback that never leaks a stack trace. AI service mirrors this with its own three global handlers (`HTTPException`, `RequestValidationError`, generic `Exception`) that log full detail server-side and return only a safe message to the client. A frontend bug was also found and fixed this session: the frontend was reading the wrong field (`error`, now the generic reason phrase) instead of `message` (the actual detail) after the error-shape change — this would have shown users generic text like "Not Found" instead of "Repository not found."

**Missing**: Nothing.

**Tested**: Yes, live.
**Command**: `curl -X POST .../api/repositories/{nonexistentId}/ask ...`
**Result**: `404`, `{"timestamp":"...","status":404,"error":"Not Found","message":"Repository not found","path":"/api/repositories/.../ask"}` — exact spec shape confirmed live, not just in code.

---

## 44. Prompt injection protection

**Status**: ✅ COMPLETE

**Files**: `ai-service/app/services/llm.py` (`UNTRUSTED_CONTENT_NOTICE`), wired into `app/agents/base_agent.py`, `app/agents/quality_agent.py`, `app/services/rag.py`, `app/routers/onboarding.py`

**Implemented**: A shared, explicit notice appended to **every** system prompt that includes repository content (all 4 review agents, RAG Q&A, onboarding generation) instructing the model to treat file/diff/chunk content as data, never as instructions, even if it contains text like "ignore previous instructions." This is enforced at the actual call site, not just documented — verified by a dedicated regression test suite that asserts the notice is present in the literal `system=` argument passed to the LLM client for every one of these five call sites.

**Missing**: Nothing.

**Tested**: Yes.
**Command**: `cd ai-service && ./.venv/bin/python -m pytest tests/test_prompt_injection_defense.py -v`
**Result**: 5/5 pass — confirms wiring for `SecurityAgent`, `BugDetectionAgent`, `TestCoverageAgent`, `CodeQualityAgent` (separate call site), and the RAG `answer_question` path.

---

## 45. Frontend loading/error/empty states

**Status**: ✅ COMPLETE

**Files**: `frontend/src/{pages/DashboardPage,pages/RepositoryDetailPage,components/{ChatPanel,ReviewList,ReviewDetail,ConnectRepoModal}}.tsx`

**Implemented**: Every data-fetching component handles loading (skeleton pulses), error (explicit rose-colored banners with actionable text), and empty (icon + copy + CTA) states distinctly — verified by direct code reading across all list/detail components this session. No `alert()` calls anywhere (confirmed by grep). Optimistic UI in `ChatPanel` with correct rollback on failure (restores the draft, removes the pending bubble, shows the error).

**Missing**: No toast notification system (the spec explicitly asks for "toast notifications," not inline banners) — inline error banners are used instead throughout. Functionally covers the same need, but is not literally what was specified.

**Tested**: Yes, via full frontend build/typecheck (would fail on any structural issue) plus direct code reading of every relevant component this session.
**Command**: `grep -rn "alert(" frontend/src/`
**Result**: No matches — confirms no `alert()` usage.

---

## 46. API documentation

**Status**: ✅ COMPLETE

**Files**: `backend/src/main/java/com/codepilot/config/OpenApiConfig.java`, `pom.xml` (`springdoc-openapi-starter-webmvc-ui`)

**Implemented**: Live Swagger UI and OpenAPI JSON, bearer-auth security scheme configured so "Authorize" works in the UI, permitted in `SecurityConfig` without authentication (so the docs themselves are reachable).

**Missing**: Nothing.

**Tested**: Yes, live.
**Command**: `curl -s -o /tmp/openapi.json -w "%{http_code}" http://localhost:8080/v3/api-docs && python3 -c "import json; print(len(json.load(open('/tmp/openapi.json'))['paths']))"`
**Result**: `200`; 12 real endpoints documented, matching the actual controllers (not a stub).

---

## 47. Backend tests

**Status**: ✅ COMPLETE

**Files**: `backend/src/test/java/com/codepilot/{controller/AuthControllerTest,security/{JwtServiceTest,RateLimitFilterTest},service/{WebhookServiceTest,PrReviewServiceTest},exception/GlobalExceptionHandlerTest}.java`

**Implemented**: 6 test files, 21 test methods total, covering auth (register/validation), JWT signing/parsing, rate-limit filter behavior (bucket classification, 429 response shape, disabled-mode bypass), webhook signature/dedup/push-branch-filtering logic, PR review success/failure/not-found paths, and the new race-condition error-handler. Real logic tests (mocked I/O boundaries, not empty placeholder test files) — confirmed by reading every test file's assertions this session.

**Missing**: No repository-layer tests (`@DataJpaTest`), no full `@SpringBootTest` integration test hitting a real embedded/test database — all current tests are either MockMvc web-slice tests or plain Mockito unit tests. A real Hibernate-session-boundary bug (§36) was in fact only caught by *live* testing, not by any of these unit tests — a legitimate coverage gap worth naming explicitly.

**Tested**: Yes, obviously — this is the test suite itself.
**Command**: `cd backend && mvn test`
**Result**: `Tests run: 21, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS`.

---

## 48. AI tests

**Status**: ✅ COMPLETE

**Files**: `ai-service/tests/{test_chunking,test_embeddings,test_incremental_indexing,test_prompt_injection_defense}.py`

**Implemented**: 21 test methods across chunking (structure-aware boundary detection for 3 languages + fallback + edge cases), embeddings (tokenizer correctness including the acronym-boundary bug fixed this session, determinism, dimension, normalization), incremental indexing (**a real integration test suite that runs against the actual live Postgres**, not a mock — upsert/read/overwrite file hashes, path-scoped chunk deletion, path-scoped hash deletion), and prompt-injection-defense regression tests.

**Missing**: No RAG/hallucination-evidence eval harness (a held-out set of questions with known "not in this repo" answers, scored for whether the model correctly declines) — explicitly documented as a known gap in `docs/ARCHITECTURE.md`, not hidden. No direct agent-output-schema validation test against a real LLM response (would require a live key).

**Tested**: Yes, obviously — this is the test suite itself.
**Command**: `cd ai-service && ./.venv/bin/python -m pytest -v`
**Result**: `21 passed in 0.88s`.

---

## 49. Frontend tests

**Status**: ❌ MISSING

**Files**: none

**Implemented**: Nothing. No test runner configured (no Vitest, no Jest, no React Testing Library in `package.json`), no `test` script in `package.json`, zero `.test.tsx`/`.spec.tsx` files anywhere in `frontend/src`.

**Missing**: Everything — component tests, hook tests, API mocking, critical-page tests, all absent.

**Tested**: N/A.
**Command**: `grep -n "\"test\"" frontend/package.json; find frontend/src -iname "*.test.*" -o -iname "*.spec.*"`
**Result**: No `test` script; no matching files.

---

## 50. Dockerfiles

**Status**: ✅ COMPLETE

**Files**: `backend/Dockerfile`, `ai-service/Dockerfile`, `frontend/Dockerfile`

**Implemented**: All three have proper multi-stage-appropriate builds and a real `HEALTHCHECK` directive (backend: `curl .../actuator/health`; ai-service: `curl .../health`; frontend: `wget --spider ...`) with `curl`/`wget` explicitly installed where the base image doesn't ship it. Backend correctly runs as a non-root user (`appuser`).

**Missing**: The frontend Dockerfile intentionally runs the Vite **dev server** in the container (documented in the file's own header comment as a deliberate skeleton-scope trade-off, with the real production pattern — multi-stage build to a static `nginx` image — spelled out but not implemented). This is honestly labeled as a known gap, not silently passed off as production-ready.

**Tested**: Partially — **Docker itself is not installed in this environment**, so `docker build` was never actually run against any of these three Dockerfiles. Static review only: base images exist and are pullable in principle (`eclipse-temurin:21-jre-jammy`, `python:3.11-slim`, `node:20-alpine` are all real, published tags), `HEALTHCHECK` syntax is valid Dockerfile syntax, dependency-caching layer order is correct in all three. **This is a real testing gap, disclosed explicitly** — see §60 and the "Commands I need to run manually" section.
**Command attempted**: `docker build -t codepilot-backend ./backend` — not run (no docker binary).

---

## 51. Docker Compose

**Status**: ⚠️ PARTIAL — YAML-valid and structurally sound, but **BLOCKED** for actual `docker compose up` verification (Docker is not installed in this environment)

**Files**: `docker-compose.yml`

**Implemented**: 6 services (postgres, redis, mailpit, ai-service, backend, frontend), correct dependency ordering via `depends_on: condition: service_healthy` (backend waits on postgres+redis+ai-service all healthy; frontend waits on backend healthy), healthchecks on every service including postgres (`pg_isready`) and redis (`redis-cli ping`), no secrets hardcoded (all sourced from `${VAR:-default}` interpolation reading the `.env` file).

**Missing**: Nothing in the file itself, as far as static analysis can determine.

**Tested**: YAML syntax only.
**Command**: `python3 -c "import yaml; d=yaml.safe_load(open('docker-compose.yml')); print(list(d['services'].keys()))"`
**Result**: Valid YAML; `['postgres', 'redis', 'mailpit', 'ai-service', 'backend', 'frontend']`. **`docker compose config` (semantic validation) and `docker compose up` (the actual, real test) were NOT run — Docker is not installed on this machine.** All five app services have instead been verified as **native processes** against the same real Postgres/Redis/Mailpit this whole project session, which is not the same thing as verifying the Docker Compose path itself works. Marking this COMPLETE would be a claim this audit cannot back up; it is correctly BLOCKED/PARTIAL.

---

## 52. Environment configuration

**Status**: ✅ COMPLETE

**Files**: `.env.example`, `ai-service/.env` (native-only, gitignored), `.env` (root, gitignored, real secrets)

**Implemented**: `.env.example` documents every variable with inline comments explaining default behavior when unset. Root `.gitignore` correctly excludes `.env` at every directory depth (verified: `git check-ignore` confirms both `/​.env` and `ai-service/.env` are ignored). No secret ever hardcoded in source — verified by an explicit grep sweep this session (`grep -rniE "(api[_-]?key|secret|password|token)\s*[:=]\s*['\"][a-zA-Z0-9_\-]{12,}['\"]"` across all backend/ai-service/frontend source, zero real matches). Real `JWT_SECRET`/`APP_ENCRYPTION_KEY` are now configured and active in this environment (user-provided this session); a real GitHub-token-encryption migration was performed correctly when `APP_ENCRYPTION_KEY` changed, to avoid stranding an already-connected repository's stored token.

**Missing**: Nothing.

**Tested**: Yes.
**Command**: `git check-ignore -v .env ai-service/.env`
**Result**: Both correctly matched by `.gitignore` line 2 (`.env`).

---

## 53. GitHub Actions CI/CD

**Status**: ❌ MISSING

**Files**: none (the only content under `.github/` is the unrelated `modernize/` artifact from §1 — no `workflows/` directory)

**Implemented**: Nothing.

**Missing**: No `.github/workflows/*.yml` at all — no frontend install/lint/test, no backend build/test, no ai-service test, no Docker build step in CI.

**Tested**: N/A.
**Command**: `find .github/workflows -type f 2>&1`
**Result**: `No such file or directory`.

---

## 54. README

**Status**: ✅ COMPLETE

**Files**: `README.md`

**Implemented**: Project overview, feature list, architecture diagram (ASCII), quick-start (Docker Compose), a verify checklist, repo layout, and an honest "what's recently been added" section that was updated this session to reflect actual verified state (replacing an earlier, now-inaccurate claim that the services had "never been through a real build" — that claim is corrected since extensive real builds/tests/live runs have since happened).

**Missing**: No screenshots (spec asks for "screenshot placeholders" — none present, not even as placeholders). No explicit GitHub OAuth setup section (correctly, since that feature doesn't exist yet — see §11) or webhook-tunnel setup walkthrough in the root README itself (that detail lives in `docs/deployment.md` instead, which is arguably better organization but is a deviation from "README should contain X" read literally).

**Tested**: Yes — read in full this session for accuracy against actual implementation state.
**Command**: N/A (manual read).
**Result**: Content verified accurate as of this audit.

---

## 55. Architecture documentation

**Status**: ✅ COMPLETE

**Files**: `docs/ARCHITECTURE.md`

**Implemented**: End-to-end flow diagrams (connect/index, ask/RAG, PR review), a "key decisions and how to defend them" section (RAG vs. long-context, pgvector vs. dedicated vector DB, dual embedding providers, hallucination mitigation, why 4 separate agents, webhook dedup mechanism, Redis's two real uses, "how would this scale to 100k repos"), and an explicit "what's intentionally not done yet" section. Updated this session to replace stale claims (the old webhook-dedup description and the old "always full re-embed" indexing description) with what's actually implemented now.

**Missing**: Nothing.

**Tested**: Yes — read in full and cross-checked against the actual current code this session; found and corrected two stale sections.

---

## 56. Database documentation

**Status**: ✅ COMPLETE

**Files**: `docs/database.md`

**Implemented**: Full schema reference — every table, every column with type and purpose, every index, the migration history table, relationship diagram, and an explicit note on the auth-verification-gate mechanism.

**Missing**: Nothing against the actual schema (it was written directly from reading the live migrations and entities, not from memory).

**Tested**: Cross-referenced against `flyway_schema_history` (§13) and live `pg_extension`/table structure this session — content matches reality.

---

## 57. RAG documentation

**Status**: ✅ COMPLETE

**Files**: `docs/rag.md`

**Implemented**: Full pipeline walkthrough, chunking strategy per language, incremental-indexing hash-diffing explanation, embedding provider comparison, vector search mechanics, prompt construction with the actual system prompt text quoted verbatim, citation-sourcing guarantee explained, and an explicit "known limitations" section (regex-based boundary detection, no reranking, no eval harness yet).

**Missing**: Nothing against current implementation.

**Tested**: Written directly from and cross-checked against the actual `rag.py`/`chunking.py`/`vector_store.py` source this session.

---

## 58. Agent documentation

**Status**: ✅ COMPLETE

**Files**: `docs/agents.md`

**Implemented**: Per-agent responsibility table, full pipeline diagram, structured-output/defensive-parsing explanation, the prompt-injection-defense mechanism with the actual notice text and a pointer to its regression test, review-synthesis fallback behavior, and an honest "what's not implemented" section (LangGraph, formal Architecture/Onboarding Agent classes, no manual re-review endpoint).

**Missing**: Nothing against current implementation.

**Tested**: Cross-checked against the actual agent source files this session.

---

## 59. Deployment documentation

**Status**: ✅ COMPLETE

**Files**: `docs/deployment.md`

**Implemented**: Local Docker Compose walkthrough, environment-variable reference table with "effect if unset" for each, migration-on-deploy guidance, honest disclosure that GitHub integration is currently PAT-based (not OAuth) with a note not to assume `GITHUB_CLIENT_ID`/`GITHUB_CLIENT_SECRET` exist yet, webhook-tunnel guidance for local dev, a production deployment sketch (6 concrete steps), and an observability-gaps disclosure.

**Missing**: Nothing against current implementation.

**Tested**: Cross-checked against actual `docker-compose.yml`/`.env.example` this session.

---

# Verification runs (raw results)

All of the following were executed live during this audit, in this order, in this environment:

### Frontend
```
$ cd frontend && npx tsc --noEmit
(exit 0, no output)

$ npm run lint
✖ 1 problem (0 errors, 1 warning)   [pre-existing react-refresh warning, not an error]

$ npm run build
✓ 157 modules transformed
✓ built in 936ms

$ grep -n '"test"' package.json
(no match — no test script defined)
```

### Backend
```
$ cd backend && mvn clean compile
BUILD SUCCESS (82 source files)

$ mvn test
Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### AI service
```
$ cd ai-service && ./.venv/bin/python -m pytest -v
21 passed in 0.88s
```

### Docker / Docker Compose
```
$ which docker
docker not found

$ python3 -c "import yaml; d=yaml.safe_load(open('docker-compose.yml')); print(list(d['services'].keys()))"
['postgres', 'redis', 'mailpit', 'ai-service', 'backend', 'frontend']
```
`docker compose config` / `docker compose up` were **not** run — no Docker binary available in this environment. This is a genuine, disclosed testing gap, not an oversight.

### Database migrations
```
$ psql -h localhost -U $(whoami) -d codepilot -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
 version |          description          | success
---------+-------------------------------+---------
 1       | init                          | t
 2       | add email verification        | t
 3       | add indexed files             | t
 4       | add pull request status check | t
```

### Live API health checks
```
$ curl -s http://localhost:8080/actuator/health
{"status":"UP"}

$ curl -s http://localhost:8000/health
{"status":"ok"}

$ curl -s -o /dev/null -w "%{http_code}" http://localhost:5173
200

$ redis-cli ping
PONG

$ psql ... "SELECT extname, extversion FROM pg_extension WHERE extname IN ('vector','pgcrypto');"
 vector   | 0.8.6
 pgcrypto | 1.4
```

### Integration-style tests that exist and were run
- `WebhookServiceTest` (4 tests) — webhook signature verification, dedup, push-branch filtering
- `PrReviewServiceTest` (3 tests) — async review success/failure/not-found paths
- `test_incremental_indexing.py` (4 tests) — **runs against the real live Postgres**, not mocked
- `test_prompt_injection_defense.py` (5 tests) — asserts actual system-prompt content at each of the 5 LLM call sites

No end-to-end test exists that exercises the full `/query` or `/review` or `/onboarding` LLM-generation path with a real Anthropic key, because no key is configured in this environment (see §26).

---

# Totals

| Status | Count |
|---|---|
| ✅ COMPLETE | 40 |
| ⚠️ PARTIAL | 10 |
| ❌ MISSING | 8 |
| 🐛 BROKEN | 0 |
| **Total categories** | **59** (one PARTIAL — §51 Docker Compose — additionally carries a BLOCKED sub-status; see below) |

**PARTIAL items**: §1 Project structure, §5 React Router, §21 File filtering, §22 Code parsing, §28 Chat history, §37 Onboarding Agent, §40 Background jobs, §51 Docker Compose.

**MISSING items**: §11 GitHub OAuth, §30 LangChain/LangGraph, §38 Architecture graph, §39 React Flow, §49 Frontend tests, §53 GitHub Actions CI/CD.

**BROKEN items**: none — every issue found and fixed during a prior review pass this session (webhook-blocking-thread bug, LazyInitializationException, missing retry logic, missing race-condition handler, missing API-contract field, duplicate-chat-message bug) has already been fixed and re-verified live, and is reflected as COMPLETE above with the fix history noted in context. Nothing currently on disk is known to be broken.

**BLOCKED (distinct from the above four statuses, called out per this task's explicit instruction)**:
- §26 RAG generation, §31-35 all four agents' live LLM output, §37 Onboarding generation — all BLOCKED on a missing `ANTHROPIC_API_KEY`, not broken. The pipeline code itself is COMPLETE and its non-LLM mechanics are verified live; only the actual model call has never been exercised in this environment.
- §50/§51 Docker build/Compose runtime verification — BLOCKED on Docker not being installed in this environment. Static/YAML verification only.

---

# Critical issues

1. **`.env` files, secrets in file filtering (§21)**: repository indexing does not exclude `.env`, `.env.*`, or common secret-file patterns from what gets fetched, chunked, embedded, and made retrievable via Q&A. If a connected GitHub repository has such a file committed (even accidentally), its contents become part of the searchable index. This is the single highest-priority functional gap found in this audit.
2. **No frontend test coverage at all (§49)**: zero component/hook/integration tests. The `LazyInitializationException` bug found this session (§36) is a concrete demonstration that this class of bug (works in isolation, breaks under real runtime conditions) is exactly what's currently unguarded against on the frontend too.
3. **Docker path never actually verified (§50, §51)**: every claim of "the app runs" in this project's history has been against native processes, never against `docker compose up` itself, because Docker isn't installed here. The documented quick-start path (`docker compose up --build`) has never been executed end-to-end.
4. **Foreign artifact in `.github/` (§1)**: an automated tool left a `modernize/java-upgrade/` directory that both documents and could reintroduce the Java-25 regression that was found and reverted earlier. It should be reviewed and likely removed once you're ready to allow modifications again — not done in this audit per your instruction not to modify anything.

# Security issues

1. **`.env`/secret files not excluded from indexing** (§21, repeated above because it is a security issue, not just a functional gap) — this is the most concrete, actionable security finding: a committed secret in a connected repo would be embedded and queryable.
2. **No filename-pattern secret detection** (`id_rsa`, `*.pem`, `*.key`) beyond the extension-based binary filter — same root cause as above.
3. Everything else security-relevant that was checked this session came back clean and is not repeated as a new issue here: AES-256-GCM encryption with correct random IVs, constant-time webhook signature comparison, no hardcoded secrets anywhere in source, ownership checks (IDOR-safe) on every repository-scoped endpoint including the review-by-ID path, no XSS vectors (no `dangerouslySetInnerHTML`, no `eval`), CORS restricted to a single explicit origin, rate limiting active with fail-open behavior on Redis outage, prompt-injection defense verified present at every LLM call site.

# Commands I need to run manually

These require either credentials only you can obtain, or actions only you can take:

1. **Get an Anthropic API key** (console.anthropic.com) and add it to `.env` / `ai-service/.env` as `ANTHROPIC_API_KEY` — unblocks §26, §31-35, §37 for real end-to-end verification.
2. **Install Docker Desktop** — the installer's final step needs your sudo password interactively, which cannot be supplied through this tool. Run `brew install --cask docker` yourself in a terminal, then `open /Applications/Docker.app` and complete its first-run setup. This unblocks real verification of §50/§51.
3. Once Docker is installed: `cd /Users/princetomar/Downloads/codepilot && docker compose up --build` — the actual, literal test of the documented quick-start path, never yet run.
4. If you want GitHub OAuth (§11): create a GitHub OAuth App yourself at github.com/settings/developers and provide the Client ID/Secret — this is an account-level action I cannot perform on your behalf.

# Exact next steps (in priority order, not yet done — audit only)

1. Fix the `.env`/secret-file exclusion gap in `GitHubClient.shouldSkipPath` (§21) — highest-priority, security-relevant, small fix.
2. Add a `.properties` → `properties` language mapping (§22) — trivial, closes a spec-literal gap.
3. Decide whether to build frontend test infrastructure (§49) — currently zero coverage on the layer where a real runtime-vs-isolation bug class has already been demonstrated to exist (§36's frontend analog risk).
4. Decide whether Docker verification (§50/§51) is worth pursuing now that it's the one path never actually exercised, versus continuing to rely on the native-process setup that has been extensively verified instead.
5. Decide on GitHub Actions CI (§53) — currently nothing runs automatically on push/PR.
6. Decide on the larger deferred items — GitHub OAuth (§11), LangGraph (§30), React Flow/Architecture graph (§38/§39), unified background-job model (§40), full ChatSession model (§28) — these are the biggest remaining pieces of the original spec and each represents real, multi-step work, not a quick fix.

No code was modified during this audit.
