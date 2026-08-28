# CodePilot — AI Codebase Intelligence & Developer Assistant

CodePilot connects to a GitHub repository, indexes it into a RAG (retrieval-augmented generation)
knowledge base, and gives a developer four things on top of that index:

1. **Codebase Q&A (chatbot)** — ask questions in plain English and get answers grounded in the
   actual code, with file + line citations ("Where is authentication implemented?"). Falls back to
   general knowledge (clearly labeled, no false citations) for off-topic questions, and refuses
   rather than guessing when nothing relevant is indexed.
2. **Code search** — the same hybrid retrieval as the chatbot, without the LLM step: exact
   keyword/symbol/filename matches and vector-similarity matches, ranked and returned directly.
3. **Agentic PR review** — four specialized AI agents (bugs, security, test coverage, code
   quality/performance) analyze a pull request's changed files concurrently and produce a merged
   review report, triggered automatically by a GitHub webhook or manually from the UI.
4. **Onboarding docs** — auto-generated architecture overview, important modules, setup
   instructions, data flow, and a "read this first" file list for a new engineer joining the repo.
5. **Architecture graph** — a visual, auto-derived module/dependency graph of the indexed repo.

Each of these works end-to-end against real GitHub repositories with real LLM calls — see
[Testing](#testing) below.

## Architecture

```
┌─────────────┐      REST/JWT      ┌──────────────────┐      REST      ┌──────────────────┐
│  frontend   │ ─────────────────► │      backend      │ ─────────────► │    ai-service     │
│ React + TS  │ ◄───────────────── │  Spring Boot 3    │ ◄───────────── │  Python + FastAPI  │
└─────────────┘                    │  auth, GitHub API, │                │  chunking, RAG,    │
                                    │  webhooks, Redis   │                │  embeddings, agents │
                                    └─────────┬─────────┘                └─────────┬─────────┘
                                              │                                     │
                                              ▼                                     ▼
                                        ┌───────────┐                       ┌──────────────┐
                                        │   Redis   │                       │  PostgreSQL   │
                                        │  (cache)  │                       │  + pgvector   │
                                        └───────────┘                       └──────────────┘
```

- **backend** (Spring Boot / Java) owns auth, GitHub integration, webhooks, business entities, and
  orchestrates the other two by calling `ai-service` over HTTP.
- **ai-service** (Python / FastAPI) owns everything AI: chunking, embeddings, hybrid vector +
  keyword retrieval, the RAG query pipeline, and the four concurrent review agents.
- **frontend** (React / TypeScript) is the developer-facing dashboard: connect a repo, chat with
  it, search it, browse PR reviews, read the onboarding doc, view the architecture graph.

They share one PostgreSQL database — the backend owns the business tables (via Flyway
migrations), the ai-service reads/writes only the `code_chunks` vector table. See
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the full design writeup,
[`docs/database.md`](docs/database.md) for the schema, [`docs/rag.md`](docs/rag.md) for the
retrieval pipeline, and [`docs/agents.md`](docs/agents.md) for the review/onboarding agents.

## Tech stack

| Layer | Technology |
|---|---|
| Frontend | React 18, TypeScript, Vite, TanStack Query, Tailwind CSS, React Router 7, Vitest + Testing Library |
| Backend | Spring Boot 3.3.4, Java 21, Spring Security (JWT), Spring Data JPA, Flyway, JUnit 5 + Mockito |
| AI service | Python, FastAPI, SQLAlchemy (async), Pydantic, pytest |
| Database | PostgreSQL 16 + pgvector (HNSW index for similarity search) |
| Cache / rate limiting / idempotency | Redis 7 (fails open — the app stays functional if Redis is unavailable) |
| LLM | Pluggable: Anthropic Claude, Google Gemini, or **Ollama** (a model running entirely on your own machine — no external API, no key, no quota) |
| Embeddings | Pluggable: a local zero-dependency hashing provider (default), or OpenAI's real embedding models |
| Containerization | Docker / docker-compose (Postgres, Redis, Mailpit, ai-service, backend, frontend, and optionally Ollama) |

## Features

- **Auth**: email/password with email verification (code + link), and "Continue with GitHub"
  OAuth as a second login option. JWT-based sessions.
- **Repository connection**: pick from your own GitHub repos (via OAuth token), connect *any*
  public repo or one you collaborate on by owner/name, or paste a personal access token manually.
- **Indexing**: chunks source by file/symbol, skips `.git`/`node_modules`/build output/binaries/
  dependency lockfiles/anything that looks like a secret, and re-indexes incrementally via
  SHA-256 file-hash diffing (unchanged files are skipped, not re-embedded).
- **Hybrid retrieval**: vector similarity (pgvector, HNSW-indexed) combined with exact
  keyword/symbol/filename matching, weighted by how rare each keyword actually is in the repo —
  so a specific term isn't buried under a common one just because it's shorter.
- **PR review**: bug, security, quality, and test-coverage agents run concurrently; findings
  include a suggested code fix (diff) where the agent can propose one; results persist and render
  in a dashboard.
- **Webhooks**: signature-verified (constant-time HMAC comparison), deduplicated via Redis so a
  redelivered GitHub webhook never produces a duplicate review or double-indexes a push.
- **Security**: every resource-scoped endpoint verifies the requesting user actually owns that
  resource (audited endpoint-by-endpoint — no way to read another user's repository by changing
  an ID); GitHub tokens and encryption keys are AES-256-GCM encrypted at rest; every LLM call that
  touches repository content carries an explicit prompt-injection defense notice, since repo
  content (READMEs, comments, diffs) is treated as untrusted data, never as instructions.

## Quick start (Docker)

```bash
cp .env.example .env        # fill in an LLM key at minimum for Q&A/review/onboarding (see below)
docker compose up --build
```

- Frontend: http://localhost:5173
- Backend API: http://localhost:8080/api (health at `/actuator/health`, interactive API docs at
  http://localhost:8080/swagger-ui/index.html)
- AI service: http://localhost:8000 (health at `/health`)
- Mailpit (catches verification emails in dev — no real mail provider needed):
  http://localhost:8025

Register a user in the UI, click the verification link (check Mailpit if you don't have a real
SMTP provider configured — see [Environment variables](#environment-variables)), sign in, connect
a GitHub repo, wait for indexing to finish, then try the Ask/Search/PR Reviews/Onboarding tabs.

**No demo/seeded account exists** — registration is self-serve. Use your own email (or any address
if you're routing mail to Mailpit locally) to create an account.

## Running natively (without Docker)

Useful for active development — this is how the project has actually been run and tested
throughout its build. Requires local Postgres (with the `pgvector` extension) and Redis.

```bash
# 1. Database + cache
brew install postgresql@16 pgvector redis && brew services start postgresql@16 redis
createdb codepilot

# 2. Backend (Spring Boot) — runs Flyway migrations automatically on startup
cd backend
export JWT_SECRET=... APP_ENCRYPTION_KEY=...   # or `set -a; source ../.env; set +a`
mvn spring-boot:run

# 3. AI service (FastAPI)
cd ai-service
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --reload --port 8000

# 4. Frontend (Vite dev server)
cd frontend
npm install
npm run dev
```

## Environment variables

Copy `.env.example` → `.env` at the repo root (read by docker-compose and by the backend when run
natively). `ai-service/.env.example` is for running the AI service natively outside Docker.
**Never commit `.env`** — it's already gitignored.

| Variable | Purpose | Default |
|---|---|---|
| `AI_PROVIDER` | `anthropic`, `gemini`, or `ollama` | `anthropic` |
| `ANTHROPIC_API_KEY` / `ANTHROPIC_MODEL` | Used when `AI_PROVIDER=anthropic` | — |
| `GEMINI_API_KEY` / `GEMINI_MODEL` | Used when `AI_PROVIDER=gemini`; free tier, no credit card, at [aistudio.google.com/apikey](https://aistudio.google.com/apikey) | — |
| `OLLAMA_BASE_URL` / `OLLAMA_MODEL` | Used when `AI_PROVIDER=ollama` — run `brew install ollama && ollama serve && ollama pull qwen2.5:7b-instruct` first | `http://localhost:11434` / `qwen2.5:7b-instruct` |
| `EMBEDDING_PROVIDER` | `local` (hashing, zero cost/deps) or `openai` (real semantic embeddings) | `local` |
| `OPENAI_API_KEY` | Required only if `EMBEDDING_PROVIDER=openai` | — |
| `JWT_SECRET` / `APP_ENCRYPTION_KEY` | **Change before deploying anywhere real.** Sign JWTs / encrypt stored GitHub tokens | dev-only insecure defaults |
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` / `GITHUB_OAUTH_REDIRECT_URI` | "Continue with GitHub" login — optional, leave blank to disable. Register an OAuth App at [github.com/settings/developers](https://github.com/settings/developers) | — |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` / `MAIL_SMTP_AUTH` / `MAIL_SMTP_STARTTLS` | Real SMTP provider for verification emails. Leave unset in Docker to use the bundled Mailpit catcher | `mailpit:1025` (Docker) |
| `CORS_ALLOWED_ORIGIN` / `FRONTEND_URL` | **Must be set to your real domain before deploying** — default only works for local dev | `http://localhost:5173` |
| `MAIL_FROM` | From-address on verification emails | `no-reply@codepilot.local` |

## API overview

All backend routes are under `/api`, JWT-authenticated except registration/login/verification and
the GitHub webhook (which is HMAC-signature-verified instead). Full interactive docs at
`/swagger-ui/index.html` once the backend is running.

| Group | Base path | Covers |
|---|---|---|
| Auth | `/api/auth` | register, login, email verification (link + 6-digit code), resend, GitHub OAuth, `/me` |
| Repositories | `/api/repositories` | list/connect/get, list-my-GitHub-repos, connect-from-GitHub (any owner) |
| Ask (Q&A) | `/api/repositories/{id}/ask`, `/qa-history` | chatbot questions + history |
| Search | `/api/repositories/{id}/search` | code search, no LLM call |
| Reviews | `/api/repositories/{id}/reviews`, `/api/reviews/{id}`, `/api/repositories/{id}/pull-requests/{n}/review` | list/get PR reviews, manual trigger |
| Onboarding | `/api/repositories/{id}/onboarding` | generated onboarding doc |
| Architecture | `/api/repositories/{id}/architecture` | module/dependency graph |
| Webhooks | `/api/webhooks/github` | GitHub push/PR events (signature-verified, not JWT) |

## Testing

```
Suite                  Command                          Result
Backend                cd backend && mvn test           64/64 passing
AI service             cd ai-service && pytest          145/145 passing
Frontend               cd frontend && npx vitest run    68/68 passing
Frontend typecheck     cd frontend && npx tsc --noEmit  clean
Frontend prod build    cd frontend && npm run build     clean
Frontend lint          cd frontend && npx eslint src    0 errors
```

Covers registration + email verification, GitHub OAuth login, connecting a repo you don't own,
incremental indexing, the chatbot with citations across multiple repos and question types,
standalone code search, a full PR review across all four agents, onboarding doc generation,
Redis-down graceful degradation, the fully-local Ollama LLM path, and a full `docker compose up
--build` boot of all six services.

One thing worth flagging if you're pointing `MAIL_HOST` at a real provider: SMTP submission
succeeding doesn't guarantee inbox delivery outside spam, so check that once when you set it up.

## Known limitations

- No free-tier LLM API has generous quota for sustained real usage (Gemini's free tier is 20
  requests/day) — use `AI_PROVIDER=ollama` for unlimited local usage, or a paid tier for production.
- GitHub webhook registration on GitHub's side isn't automated by the app — the manual "trigger
  review" button is the workaround until that's built.
- A repository renamed on GitHub after being connected (e.g. `owner/old-name` → `owner/new-name`)
  will 404 on PR review/reindex until reconnected — the app doesn't follow GitHub's redirect yet.
- The production JS bundle (~640KB) hasn't been code-split — a performance opportunity, not a bug.

## Repo layout

```
backend/       Spring Boot API — see backend/README.md
ai-service/    Python FastAPI RAG + agents service — see ai-service/README.md
frontend/      React dashboard — see frontend/README.md
docs/          ARCHITECTURE.md, database.md, rag.md, agents.md, deployment.md
docker-compose.yml
.env.example
```
