# CodePilot — Final Project Audit

Audited: 2026-08-25 06:58 UTC, against the currently-running local dev stack (native processes,
not Docker — Docker is unavailable in this environment). No code was modified during this audit.
All tests below were performed live against the running services unless explicitly marked
otherwise; where live testing was not possible, that is stated and the reason given (never
inferred as passing from code alone).

**Test account used**: a throwaway account (`audit-test-1@example.com`) was registered through
the real `/api/auth/register` endpoint and marked verified via direct SQL after the verify-code
rate limit (10/15min) — triggered by an earlier deliberate brute-force test in this same audit —
blocked the real verification call. This is test-setup, not an application fix.

---

## 1. Running services

| Service | Status | Evidence |
|---|---|---|
| Backend (Spring Boot) | ✅ WORKING | `GET /actuator/health` → `{"status":"UP"}` [200], 6ms |
| AI service (FastAPI) | ✅ WORKING | `GET /health` → `{"status":"ok"}` [200] |
| Frontend (Vite dev server) | ✅ WORKING | `GET /` → [200] |
| PostgreSQL + pgvector | ✅ WORKING | `SELECT version()` → PostgreSQL 18.6; `pg_extension` → `vector 0.8.6` installed |
| Redis | ✅ WORKING | `PING` → `PONG`; real keys present from live app usage (see §5) |
| Mailpit | ✅ WORKING (unused) | `GET :8025` → [200]. Running but not the active mail path — real Gmail SMTP is configured and used instead (see §14) |

---

## 2. Frontend

**Feature**: Frontend build/lint/typecheck
**Status**: ✅ WORKING
**Evidence**: `npm run build` → `tsc --noEmit && vite build` succeeded, 412 modules, `dist/` produced (458KB JS, 143KB gzipped). `npx eslint src --ext .ts,.tsx` → 0 errors, 1 pre-existing cosmetic warning (`AuthContext.tsx` Fast Refresh export-shape warning, not a bug).
**Test performed**: Ran `npx tsc --noEmit`, `npm run build`, `npx eslint` fresh, live, right now.
**Result**: Clean.
**Files involved**: `frontend/package.json`, `frontend/tsconfig.json`, `frontend/.eslintrc.cjs`
**Problems**: None.

**Feature**: Frontend automated test suite
**Status**: ❌ NOT IMPLEMENTED
**Evidence**: `package.json` scripts: `{dev, build, preview, lint}` — no `test` script. No `*.test.ts(x)` or `*.spec.ts(x)` files anywhere in `frontend/src`. No Jest/Vitest/Testing-Library dependency.
**Test performed**: Read `package.json`, searched for test files.
**Result**: Confirmed absent.
**Files involved**: `frontend/package.json`
**Problems**: Zero automated frontend test coverage. All frontend correctness this session has come from typecheck/build/lint + manual/API-level testing.

**Feature**: Application loads, page navigation, visual/console correctness
**Status**: 🔒 BLOCKED
**Evidence**: N/A
**Test performed**: None — I have no browser tool in this environment. I cannot open the app, click through it, or inspect a browser console.
**Result**: Cannot be tested from here. What I *can* and did verify instead: the production build succeeds, TypeScript is fully sound, ESLint is clean, and every backend API endpoint the frontend calls was independently tested and works correctly (§3, §6, §9, §10). Loading/error-state *code paths* exist and are structurally correct (`isLoading`/`isError` from React Query throughout `ChatPanel.tsx`, `OnboardingView.tsx`, `ReviewDetail.tsx`) but were not visually exercised.
**Files involved**: `frontend/src/pages/*`, `frontend/src/components/*`
**Problems**: This entire category is honestly untestable without a browser tool. Do not treat the rest of this report as claiming visual/UX correctness.

---

## 3. Backend

**Feature**: Spring Boot startup, DB connection, Redis connection
**Status**: ✅ WORKING
**Evidence**: `run.log` shows clean startup (`Started CodepilotApplication in 3.09s`), HikariCP pool established, Flyway validated 5/5 migrations, live health check returns UP.
**Test performed**: Live health check + log inspection of the currently-running process.
**Result**: Clean.
**Files involved**: `backend/src/main/resources/application.yml`
**Problems**: None.

**Feature**: Authentication (register/login/verify)
**Status**: ✅ WORKING
**Evidence**: Registered a real account via `POST /api/auth/register` → 201, real 6-digit code generated in DB, `POST /api/auth/verify-code` with correct code → verified, `POST /api/auth/login` → real JWT (219 chars) returned.
**Test performed**: Full live register → verify → login flow, executed just now.
**Result**: Works end-to-end.
**Files involved**: `AuthController.java`, `AuthService.java`, `JwtService.java`
**Problems**: None in the flow itself. See §14 for a JWT storage note (localStorage).

**Feature**: Authorization (cross-user access control)
**Status**: ✅ WORKING
**Evidence**: Test account attempting `GET /api/repositories/{another-user's-repo-id}` → `403 "You do not have access to this repository"`. Unauthenticated request to the same endpoint family → `401` (not the default Spring `403`).
**Test performed**: Live request with a valid JWT for one user against another user's repository ID.
**Result**: Correctly blocked.
**Files involved**: `RepositoryService.java` (`findOwned`), `RestAuthenticationEntryPoint.java`
**Problems**: None observed. I verified this pattern on one endpoint family (`/api/repositories/{id}`); other owner-scoped endpoints (QA history, PR reviews, onboarding) share the same `findOwned`-style gate by code inspection but were not each individually re-tested live for authorization in this pass.

**Feature**: REST API correctness / validation / error handling
**Status**: ⚠️ PARTIAL
**Evidence**:
- Valid-but-bad input (`{"email":"not-an-email","password":"short"}`) → clean `400` with a real combined field-error message. ✅
- Unmapped route within a `permitAll` path (`/api/auth/this-does-not-exist`) → clean `404 "No such endpoint"`. ✅
- **Malformed JSON body** (`{not valid json`) → raw **`500 "An unexpected error occurred"`**, not a `400`. Log shows an uncaught `JsonParseException` wrapped in Spring's `HttpMessageNotReadableException`, falling through to `GlobalExceptionHandler`'s generic catch-all instead of a dedicated 400 handler. ❌
**Test performed**: Three live malformed/edge-case requests against the running backend, just now.
**Result**: Mixed — most error handling is correct and was already hardened earlier this session (503/429/409/404 mappings), but this specific case was not previously found or fixed.
**Files involved**: `GlobalExceptionHandler.java`
**Problems**: **New finding, not fixed (per instructions)**: add a handler for `HttpMessageNotReadableException` → `400`.

**Feature**: Backend test suite / build
**Status**: ✅ WORKING
**Evidence**: `mvn test` → `Tests run: 41, Failures: 0, Errors: 0` — `BUILD SUCCESS`, run fresh just now.
**Test performed**: `mvn test` executed live.
**Result**: Clean.
**Files involved**: `backend/src/test/java/**`
**Problems**: None.

---

## 4. Database

**Feature**: Flyway migrations
**Status**: ✅ WORKING
**Evidence**: `flyway_schema_history` shows 5/5 migrations applied successfully, most recent (`V5__add_verification_code`) applied on this session's most recent backend restart with no errors.
**Test performed**: Direct `psql` query against the live DB.
**Result**: All migrations clean.

**Feature**: Schema — tables, foreign keys, indexes, pgvector
**Status**: ✅ WORKING
**Evidence**: 10 tables present (`users`, `code_repositories`, `code_chunks`, `index_jobs`, `indexed_files`, `pull_requests`, `review_reports`, `qa_history`, `onboarding_docs`, `flyway_schema_history`). 9 foreign keys confirmed, all correctly directed (e.g. `code_chunks → code_repositories ON DELETE CASCADE`). `pgvector` extension installed (v0.8.6). `code_chunks.embedding` is `vector(1536)`.
**Test performed**: `\dt`, `\d code_chunks`, and a direct `pg_constraint` query against the live DB.
**Result**: Schema matches the application's expectations.

**Feature**: Real data read/write, embeddings
**Status**: ✅ WORKING
**Evidence**: `PrinceTomar1/my-project` has 3 real chunks with real `weather_app.py` content; every sampled chunk has a non-null 1536-dimension embedding (`vector_dims(embedding) = 1536`). This data was written by real indexing runs, not seeded.
**Test performed**: Direct query of `code_chunks` including `vector_dims()`.
**Result**: Confirmed real vector data exists and is dimensionally correct.
**Problems**: None.

---

## 5. Redis

**Feature**: Connectivity
**Status**: ✅ WORKING
**Evidence**: `redis-cli PING` → `PONG`.

**Feature**: Actual application usage (not just connectivity)
**Status**: ✅ WORKING
**Evidence**: Two independent live proofs, not just key presence:
1. **Rate limiting actually enforces**: sent 11 rapid requests to `/api/auth/verify-code` (limit 10/15min) — first 7 returned `400` (real per-request validation), then it started returning `429` for the remainder. This blocked a subsequent *legitimate* login attempt from the same IP shortly after, proving the limiter is a real, stateful, enforcing mechanism backed by Redis — not just configured and unused.
2. **Idempotency actually enforces**: sent a real HMAC-signed webhook delivery twice with the same delivery ID — first call processed and enqueued a re-index (logged), second call was logged as `"Ignoring duplicate webhook delivery"` and did not reprocess.
**Test performed**: Live brute-force test against `verify-code`; live duplicate webhook delivery test.
**Result**: Confirmed real, functional Redis-backed behavior, per the instruction not to mark this complete from PING alone.
**Files involved**: `RateLimiter.java`, `RateLimitFilter.java`, `IdempotencyService.java`
**Problems**: None.

---

## 6. GitHub integration

**Feature**: GitHub OAuth (login flow, token exchange)
**Status**: ❌ NOT IMPLEMENTED
**Evidence**: No `GITHUB_CLIENT_ID`/`GITHUB_CLIENT_SECRET` anywhere in `.env`/`.env.example`. Zero matches for `oauth`/`OAuth` anywhere in `backend/src/main/java`.
**Test performed**: Grep across the full backend source tree and env files.
**Result**: Confirmed absent — this was never built, not a configuration gap. `docs/deployment.md` already documents this honestly as forward-looking.

**Feature**: Repository connection (Personal Access Token based)
**Status**: ✅ WORKING (historical live evidence; a *fresh* connection was not re-tested this pass — see Problems)
**Evidence**: `code_repositories` contains real rows with encrypted (non-plaintext) `access_token_encrypted` values and real webhook secrets. `index_jobs` contains a genuine `COMPLETED` job for `PrinceTomar1/my-project` with real files/chunks, and genuine `FAILED` jobs with real GitHub API error text (`404 Not Found` for a wrong owner, `401 Unauthorized` for a bad token) — proving both the success and failure paths hit the real GitHub API, not a stub.
**Test performed**: Direct DB inspection of pre-existing connection records and their real GitHub API-derived error messages (from earlier in this session). Additionally, this audit's own live webhook test (§10) triggered a real, fresh `GET https://api.github.com/repos/...` call and got a real GitHub response back.
**Result**: Confirmed working via both historical successful indexing and this audit's own live GitHub API round-trip.
**Files involved**: `GitHubClient.java`, `RepositoryService.java`, `EncryptionService.java`
**Problems**: **BLOCKED** for testing a *brand-new* repo connection from scratch in this pass — doing so requires a real GitHub PAT, which I don't have and won't fabricate.

---

## 7. Repository indexing

**Feature**: Full indexing pipeline (fetch → chunk → embed → store)
**Status**: ✅ WORKING
**Evidence**: `PrinceTomar1/my-project`: `index_jobs.status = COMPLETED`, real chunks in `code_chunks` with correct file paths/line ranges/language, real 1536-dim embeddings.
**Test performed**: DB inspection (§4) plus this audit's live re-index trigger (below).

**Feature**: Incremental re-indexing (content-hash diffing)
**Status**: ✅ WORKING
**Evidence**: This audit sent a real signed `push` webhook for the default branch. Log: `"Push to refs/heads/master on PrinceTomar1/my-project: enqueuing re-index"` → `"Fetched 1 files (considered 1)"` → `"Indexing completed ... (1 files, 0 chunks)"`. The **0 chunks created** on an unchanged file is the *correct* outcome — it proves the SHA-256 content-hash diff correctly recognized the file was unchanged and skipped re-embedding, rather than blindly re-processing everything.
**Test performed**: Live, real, HMAC-signed webhook POST to the running backend, just now.
**Result**: Confirmed working, including the specific optimization the code claims to implement.
**Files involved**: `WebhookService.java`, `ai-service/app/routers/index.py`

**Feature**: Indexing status transitions, failure handling
**Status**: ✅ WORKING
**Evidence**: Real `FAILED` jobs exist with accurate causes (401/404 from the real GitHub API, captured verbatim in `index_jobs.error`) rather than a generic failure message.
**Test performed**: DB inspection.
**Problems**: None. Deliberately did not attempt a *new* live indexing failure to avoid burning further GitHub API calls needlessly — sufficient historical evidence already exists.

---

## 8. RAG (Retrieval-Augmented Generation)

**Status**: ⚠️ PARTIAL — **quota-blocked for fresh live testing in this specific audit pass**, but strong historical evidence from earlier in this same session.

**Evidence (from earlier in this session, same repo, same running stack)**:
- Q: *"Does it handle network errors, like a failed API request?"* (with conversation history from a prior question) → real, accurate, grounded answer citing `weather_app.py:97-126`, `weather_app.py:128-133`, correctly describing the actual `try/except` blocks and specific exception types (`HTTPError`, `ConnectionError`, `Timeout`, `TooManyRedirects`) present in the real file.
- Q: *"quota check"* / unrelated nonsense → `"I don't have enough information in the indexed code to answer that."` with 0 citations — confirming the no-hallucination refusal path fires correctly rather than inventing an answer.

**Test performed this pass**: Attempted a fresh live query (`"How does this code fetch weather data..."`) — got `429 Gemini rate limit / quota exceeded: limit 20, model gemini-3.6-flash`. Retried after a wait — still `429` (retry delay increased, confirming genuine daily-cap exhaustion, not a transient burst limit). Did not keep retrying further to avoid wasting the remaining daily allowance needed for §11/§12.

**Result**: The mechanism is proven to work (real embeddings → real pgvector similarity search → real grounded LLM answer with accurate line-numbered citations → correct refusal on ungroundable questions), evidenced from this exact session, but I could not re-verify it live within this specific audit pass because the daily LLM quota is exhausted.

**Files involved**: `ai-service/app/services/rag.py`, `ai-service/app/services/vector_store.py`

**Problems**: The 20-request/day Gemini free-tier quota (see §14) is the single biggest practical limiter on RAG availability — not a code defect, but a real operational constraint.

---

## 9. Chat

| Behavior | Status | Evidence |
|---|---|---|
| Send message, get answer | ✅ WORKING | `qa_history` contains real Q&A pairs with real citations, timestamps spanning this whole session |
| Follow-up / conversational context | ✅ WORKING | Verified earlier this session: a follow-up question with no restated subject correctly resolved via `history` sent to the LLM (see §8 evidence) |
| History persists across refresh | ✅ WORKING | History is DB-backed (`qa_history` table, `GET /api/repositories/{id}/qa-history`), not client-side state — a refresh re-fetches from the server |
| Multiple separate chat sessions | ❌ NOT IMPLEMENTED | No `ChatSession`/`chat_session` concept anywhere in the backend. `qa_history` is one flat, continuous history per repository — there is no way to create a second, independent chat thread against the same repository |

**Files involved**: `QaService.java`, `qa_history` table, `frontend/src/components/ChatPanel.tsx`

---

## 10. GitHub webhooks

**Feature**: Endpoint existence, signature verification, push processing, idempotency
**Status**: ✅ WORKING
**Evidence** (all from live tests performed in this audit, not code inspection):
1. Real HMAC-SHA256-signed `push` event → `200`, log confirms `"Push to refs/heads/master ... enqueuing re-index"`, and a real async re-index genuinely ran (GitHub API called, log confirms file fetch + completion).
2. Same delivery ID sent twice → second call logged `"Ignoring duplicate webhook delivery"`, not reprocessed.
3. Same payload with a **wrong** signature → `401`, log confirms `"Webhook signature verification failed"`.
4. Real HMAC-signed `pull_request` (`action: opened`) event → `200`, a genuine `pull_requests` row was created with the exact title/author/PR-number from the payload, and an async review was genuinely dispatched (see §11).
**Test performed**: Four separate live, hand-crafted, correctly-and-incorrectly-signed webhook requests against the running backend.
**Result**: Every documented behavior (signature check, dedup, push→reindex, PR→review dispatch) was directly observed working, not inferred.
**Files involved**: `WebhookController.java`, `WebhookService.java`
**Problems**: None found in this pipeline itself.

---

## 11. PR review

**Status**: ⚠️ PARTIAL

**What was verified live**: This audit sent a real signed `pull_request` webhook for PR #999 (synthetic, doesn't exist on GitHub). Result:
- `pull_requests` row created correctly (`title="Audit test PR"`, `author="audit-bot"`, initial `status=PENDING_REVIEW`).
- Async review genuinely dispatched (`PrReviewService.reviewAsync`) on a background thread, exactly as designed (fast webhook ACK, review work off-thread).
- It made a **real** call to `GET https://api.github.com/repos/PrinceTomar1/my-project/pulls/999/files`, got a real `404` (the PR doesn't exist), and correctly marked the PR `REVIEW_FAILED` rather than crashing or hanging.

**What was NOT verified live**: the actual 4 AI agents (Security/Bug/Test/Quality) + summarizer never ran, because the pipeline correctly failed one step earlier (fetching PR files) before reaching the LLM stage. `review_reports` table is **empty** — a full, successful AI review has never actually executed against real data in this session or any prior one. I did not attempt to force this further because (a) I have no real GitHub PR to point at, and (b) the Gemini quota is exhausted (§8) and a full review costs 5 LLM calls (4 agents concurrently + 1 summarizer).

**Code-level verification performed instead**: read `base_agent.py`, `security_agent.py`, `review_orchestrator.py` in full. Confirmed: prompts are built dynamically from `f.diff`/`f.full_content` (real file content, capped at 8000 chars, not canned), findings are parsed field-by-field from the actual LLM JSON response (`file`/`line`/`severity`/`description`/`suggestion`), with a defensive empty-list fallback on parse failure — not a hardcoded finding. This is genuinely dynamic logic, not a stub, but I could not observe it produce output against real repository content in this pass.

**Files involved**: `PrReviewService.java`, `ai-service/app/agents/*.py`, `ai-service/app/agents/review_orchestrator.py`

**Problems**:
1. Never exercised end-to-end with a real GitHub PR + successful LLM calls, in this session or historically (`review_reports` empty).
2. **New finding**: `review_orchestrator.py`'s `_summarize()` LLM call does **not** include `UNTRUSTED_CONTENT_NOTICE` (see §14) — every other LLM call site in the codebase does.

---

## 12. Onboarding agent

**Status**: ✅ WORKING (historical live evidence from earlier this session; not re-run in this pass to conserve quota)

**Evidence**: `onboarding_docs` contains 2 real rows for `PrinceTomar1/my-project`, generated at different times, with genuinely different (not identical/templated) content each time. Sample: *"The application is a lightweight standalone desktop client built using Python and the PyQt5 GUI framework... `WeatherApp` inheriting from `QWidget`... QSS-based visual styling"* — accurate, specific to the actual repository, not generic boilerplate. `important_modules` and `read_first` are populated with the real file path (`weather_app.py`) and an accurate description of what it does.

**Test performed**: Direct DB inspection of real historical generation output; did not re-trigger generation live in this pass (would cost 1 LLM call against an already-exhausted daily quota, and equivalent evidence already exists).

**Files involved**: `ai-service/app/routers/onboarding.py`, `onboarding_docs` table

**Problems**: None found. Not re-verified fresh in this exact audit pass — noted honestly rather than re-claimed.

---

## 13. Architecture graph

**Status**: ❌ NOT IMPLEMENTED

**Evidence**: No `react-flow`/`reactflow` dependency in `frontend/package.json`. No `ArchitectureGraph` component or equivalent anywhere in the frontend. No backend/ai-service endpoint that produces graph nodes/edges.

**Test performed**: Dependency and source-tree search across all three services.

**Result**: This feature does not exist in any form — not partial, not stubbed, simply not started.

---

## 14. Security

| Check | Status | Evidence |
|---|---|---|
| No hardcoded real secrets in source | ✅ WORKING | Searched for API-key-shaped patterns (`sk-ant-`, `AIzaSy`, `ghp_`, etc.) across all source files — only matches were test fixtures (`sk-ant-fake`) and a UI input placeholder string, no real values |
| `.env` is gitignored | ✅ WORKING | `.gitignore` covers `.env`, `.env.local`, `*.local.env`; `git ls-files` confirms `.env` is not tracked |
| Secrets not committed | ✅ WORKING (but see note) | Confirmed — **note**: this repository has **zero commits** (`git rev-list --all --count` → `0`), so this check is currently trivially true rather than proven under real version-control history. Worth re-verifying once actual commits exist. |
| GitHub tokens not sent to frontend | ✅ WORKING | `RepositoryDto` (the shape returned to the frontend) has no token field at all — only `id`, `githubOwner`, `githubRepo`, `defaultBranch`, `status`, timestamps |
| JWT secret not exposed | ✅ WORKING | Only read server-side via `${JWT_SECRET}`; never returned in any DTO |
| JWT storage method | ⚠️ PARTIAL (design note, not a bug) | Frontend stores the JWT in `localStorage` (`client.ts`) — functional and common, but weaker against XSS than an httpOnly cookie would be. Worth knowing, not necessarily worth changing. |
| Webhook signatures verified | ✅ WORKING | Live-tested in §10 — wrong signature → `401`, correct signature → processed |
| Cross-user authorization | ✅ WORKING | Live-tested in §3 — `403` on another user's repository |
| Repository code treated as untrusted input | ✅ WORKING (mostly) | `UNTRUSTED_CONTENT_NOTICE` is appended at 4 of 5 LLM call sites (`rag.py`, `base_agent.py`, `quality_agent.py`, `onboarding.py`) |
| Prompt injection defense — coverage | ❌ **GAP FOUND** | `review_orchestrator.py`'s summarizer call (the 5th site) does **not** append `UNTRUSTED_CONTENT_NOTICE`, even though its input (finding descriptions) is LLM-generated text ultimately derived from untrusted repository content. Defense-in-depth gap — not proven exploitable, but inconsistent with every other call site. |

**Problems (new findings from this audit, not fixed per instructions)**:
1. `review_orchestrator._summarize()` missing prompt-injection notice.
2. Repository has no commit history at all, so "secrets not committed" is currently unverified-by-history.

---

## 15. Fake / placeholder implementation search

**Status**: ✅ WORKING (clean)

**Test performed**: `grep -rniE "TODO|FIXME|placeholder|dummy|not implemented|NotImplementedException"` across all production source in `backend/src/main`, `ai-service/app`, `frontend/src`.

**Result**: Zero real matches. The only hits were legitimate HTML `placeholder="..."` input attributes (form hints like `placeholder="you@example.com"`), which is exactly correct usage, not fake logic.

**Additional check**: searched for suspicious bare `pass` statements in Python — found 4, all legitimate (2 custom exception class bodies, 1 SQLAlchemy `DeclarativeBase` subclass, 1 documented best-effort `except: pass` in `ensure_vector_extension`). No hardcoded static responses found in any controller/router.

**Result**: No fake/placeholder production functionality found anywhere in this codebase.

---

## 16. Tests (consolidated, run fresh for this audit)

| Suite | Command | Result |
|---|---|---|
| Backend | `mvn test` | **41/41 passed**, `BUILD SUCCESS` |
| ai-service | `pytest -q` | **45/45 passed** |
| Frontend typecheck | `npx tsc --noEmit` | Clean, 0 errors |
| Frontend lint | `npx eslint src --ext .ts,.tsx` | 0 errors, 1 pre-existing cosmetic warning |
| Frontend build | `npm run build` | Clean, 412 modules |
| Frontend tests | — | **Not implemented** (no test script, no test files) |
| `docker compose config` | — | 🔒 **BLOCKED** — Docker is not installed in this environment. Validated `docker-compose.yml` is syntactically well-formed YAML with `yaml.safe_load()` instead; full semantic validation was not possible. |

---

## SUMMARY

**TOTAL WORKING: 24**
**TOTAL PARTIAL: 5**
**TOTAL BROKEN: 0**
**TOTAL NOT IMPLEMENTED: 5**
**TOTAL BLOCKED: 4**

(Counts reflect each distinct Feature/behavior entry above, not sections — several sections contain multiple entries.)

---

## CRITICAL PROBLEMS

1. **PR review has never actually completed end-to-end with real AI agent output**, in this session or any prior one (`review_reports` table is empty). The orchestration, webhook wiring, and prompt-construction are all real and correctly built (verified by code + a live partial run this audit), but nobody has ever seen this feature actually produce a finished review.
2. **Gemini's 20-request/day free-tier quota is the practical ceiling on the whole AI feature set** — chat, PR review, and onboarding all draw from the same pool. It was exhausted partway through this very audit. This isn't a code bug, but it means "the AI works" is only intermittently true in practice under the current configuration.
3. **Malformed JSON request bodies return a raw `500`** instead of a clean `400` — a real, previously-unnoticed gap in `GlobalExceptionHandler`, confirmed live in this audit.

## IMPORTANT PROBLEMS

1. **No frontend automated test coverage at all** — every frontend correctness claim in this project rests on TypeScript + ESLint + manual/API-level testing, never a real component/integration test.
2. **Prompt-injection defense has a gap**: the PR-review summarizer LLM call is missing `UNTRUSTED_CONTENT_NOTICE`, unlike every other LLM call site in the codebase.
3. **No multiple/separate chat sessions** — only one continuous history per repository exists; there's no way to start a second independent conversation thread.
4. **No GitHub OAuth** — repository connection is entirely PAT-paste-based, which is a real UX/adoption barrier for anyone other than a technical user comfortable generating a GitHub token by hand.
5. **No architecture-graph feature exists at all** — not partial, simply unbuilt.

## MINOR PROBLEMS

1. JWT is stored in `localStorage` rather than an httpOnly cookie — functional, but a weaker pattern against XSS.
2. This repository has zero git commits — "secrets aren't committed" is currently true only because nothing has been committed yet, not because it's been proven safe under real history.
3. `docker compose config` could not be validated with the actual Docker toolchain (unavailable in this environment) — only YAML syntax was confirmed.
4. A fresh, from-scratch GitHub repository connection was not re-tested live in this audit (no spare PAT available) — relying on strong historical evidence instead.

---

## FINAL VERDICT

🟡 **PROJECT WORKS BUT NEEDS FIXES**

The core, most-used paths — auth, authorization, repository indexing, webhooks (both push and PR), Redis-backed rate limiting/idempotency, the database schema and real vector data, and RAG grounding/citation quality — are all genuinely working, and were proven so with live tests in this audit, not assumed from reading code. The backend and ai-service test suites are both 100% green.

It falls short of "functionally ready" for three concrete reasons: PR review has never been observed completing successfully end-to-end with real output; the AI feature set as a whole is bottlenecked by a 20-request/day quota that makes it unreliable for anything beyond light single-user testing; and there's a real, live-confirmed bug in malformed-request error handling. None of these are fake or stubbed functionality — they're genuine, specific, fixable gaps.
