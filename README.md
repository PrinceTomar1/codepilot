# CodePilot - AI Codebase intelligence

A RAG-powered GitHub codebase assistant: connect a repository, ask questions about it in plain
English and get answers grounded in the actual code with file-level citations, and get an
agentic AI pipeline that reviews pull requests for bugs, security issues, code smells,
performance problems, and missing test coverage — automatically on every PR, or on demand.

## Live Demo

**https://frontend-production-522f.up.railway.app**

## GitHub Repository

**https://github.com/PrinceTomar1/codepilot**

## Features

- **RAG-powered codebase assistant** — ask questions in plain English ("Where is authentication
  implemented?") and get answers grounded in the actual indexed code, not generic LLM knowledge.
- **File-level references** — every chatbot answer cites the specific file and line range it drew
  from, so you can verify the answer against the real source instead of trusting it blindly.
- **Vector search** — pgvector (HNSW-indexed) similarity search combined with exact
  keyword/symbol/filename matching, so both "find code like this" and "find this exact name"
  queries work well.
- **Repository indexing** — chunks source by file/symbol, skips `.git`/`node_modules`/build
  output/binaries/dependency lockfiles/anything that looks like a secret, and re-indexes
  incrementally via SHA-256 file-hash diffing (unchanged files are never re-embedded).
- **Agentic PR review** — four specialized AI agents (bugs, security, code quality/performance,
  test coverage) analyze a pull request's changed files concurrently and produce one merged,
  categorized review report — triggered automatically by a GitHub webhook, or manually from the UI.
- **GitHub webhooks** — signature-verified (constant-time HMAC comparison) and deduplicated via
  Redis, so a redelivered GitHub webhook never produces a duplicate review or double-indexes a push.
- **GitHub integration** — connect via OAuth (pick from your own repos, or type any owner/repo you
  have read access to) or a pasted personal access token; both work the same way once connected.
- **Authentication** — email/password with 6-digit-code or link-based email verification, forgot
  /reset password, and "Continue with GitHub" OAuth as a second login option. JWT-based sessions.
- **Onboarding docs & architecture graph** — auto-generated architecture overview, key modules, a
  "read this first" file list for a new engineer, and a visual module/dependency graph — both
  derived directly from the indexed repository, not hand-written.

Every one of these has been run end-to-end in production against real GitHub repositories and
real LLM calls — see [Testing](#testing) below.

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
| Frontend | React 18, TypeScript, Vite (route-level code splitting), TanStack Query, Tailwind CSS, React Router 7, Vitest + Testing Library |
| Backend | Spring Boot 3.3.4, Java 21, Spring Security (JWT), Spring Data JPA, Flyway, JUnit 5 + Mockito |
| AI service | Python, FastAPI, SQLAlchemy (async), Pydantic, pytest |
| Database | PostgreSQL + pgvector (HNSW index for similarity search) |
| Cache / rate limiting / idempotency | Redis (fails open — the app stays functional if Redis is unavailable) |
| LLM | Pluggable: Anthropic Claude, Google Gemini, or Ollama (a model running entirely on your own machine — no external API, no key, no quota) |
| Embeddings | Pluggable: a local zero-dependency hashing provider (default), or OpenAI's real embedding models |
| Email | Pluggable: SMTP (local dev, via a bundled Mailpit catcher) or SendGrid (production — several PaaS hosts block outbound SMTP, so an HTTP-API provider is what actually delivers there) |
| Deployment | Railway (backend, ai-service, frontend, PostgreSQL, Redis); Docker / docker-compose for local/self-hosted |

## How it works

**Repository ingestion (RAG):** connecting a repo fetches its file tree from the GitHub API,
filters out anything irrelevant or secret-bearing, and sends the rest to `ai-service`. Each file
is chunked by structural boundaries (function/class, with a fallback for unsupported languages),
embedded, and stored in `pgvector` alongside a SHA-256 hash of its content. Re-indexing (e.g. after
a push webhook) re-hashes every file and only touches the ones that actually changed.

**Asking a question:** the question is embedded and matched against the repo's stored chunks
using hybrid retrieval — vector similarity plus exact keyword/symbol matching, weighted so a rare,
specific term isn't buried under a common short one. The top-ranked chunks become the LLM's
context, and the model is instructed to answer only from that context and cite exactly which
file/lines it used — it refuses rather than guessing when nothing relevant is indexed, and falls
back to clearly-labeled general knowledge (never a false citation) for off-topic questions.

**PR review:** a GitHub webhook (or a manual trigger from the UI) fetches the pull request's
changed files and diffs from GitHub, then runs four specialized agents — bugs, security, code
quality/performance, test coverage — concurrently against that content. Each agent's findings are
merged into one categorized report and persisted; a suggested code fix is included where an agent
can propose one.

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
mail provider configured — see [Environment variables](#environment-variables)), sign in, connect
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
**Never commit `.env`** — it's already gitignored, and only variable *names* are documented here
and in `.env.example`, never real values.

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
| `MAIL_PROVIDER` | `smtp` (local dev) or `sendgrid` (production — see [Deployment](#deployment)) | `smtp` |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` / `MAIL_SMTP_AUTH` / `MAIL_SMTP_STARTTLS` / `MAIL_FROM` | `MAIL_PROVIDER=smtp` only. Leave unset in Docker to use the bundled Mailpit catcher | `mailpit:1025` (Docker) |
| `SENDGRID_API_KEY` / `SENDGRID_FROM_ADDRESS` | `MAIL_PROVIDER=sendgrid` only. `SENDGRID_FROM_ADDRESS` must be a Single Sender verified in SendGrid's dashboard (no domain purchase needed) | — |
| `CORS_ALLOWED_ORIGIN` / `FRONTEND_URL` | **Must be set to your real domain before deploying** — default only works for local dev | `http://localhost:5173` |

## API overview

All backend routes are under `/api`, JWT-authenticated except registration/login/verification/
password-reset and the GitHub webhook (which is HMAC-signature-verified instead). Full interactive
docs at `/swagger-ui/index.html` once the backend is running.

| Group | Base path | Covers |
|---|---|---|
| Auth | `/api/auth` | register, login, email verification (link + 6-digit code), resend, forgot/reset password, GitHub OAuth, `/me` |
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
Backend                cd backend && mvn test           70/70 passing
AI service             cd ai-service && pytest          145/145 passing
Frontend               cd frontend && npx vitest run    68/68 passing
Frontend typecheck     cd frontend && npx tsc --noEmit  clean
Frontend prod build    cd frontend && npm run build     clean
Frontend lint          cd frontend && npx eslint src    0 errors
```

Covers registration + email verification, forgot/reset password, GitHub OAuth login, connecting a
repo you don't own, incremental indexing, the chatbot with citations across multiple repos and
question types, standalone code search, a full PR review across all four agents, onboarding doc
generation, Redis-down graceful degradation, the fully-local Ollama LLM path, and a full
`docker compose up --build` boot of all six services.

Production has been exercised the same way: registration with real email delivery, a full GitHub
PR review producing a categorized report, and a repository indexed and queried through the
deployed chatbot.

## Deployment

Live architecture: **Railway** end to end — frontend (static build served by nginx), backend,
ai-service, PostgreSQL+pgvector, and Redis all run as Railway services in the same private network.
See [`docs/deployment.md`](docs/deployment.md) for the full concrete setup steps, including exactly
which environment variables each service needs.

The one deployment-specific thing worth knowing: several PaaS hosts (Railway confirmed) block
outbound SMTP entirely on their free tier, so production email delivery uses `MAIL_PROVIDER=sendgrid`
(an HTTP API, not SMTP) rather than the SMTP setup that works fine locally.

## Known limitations

- No free-tier LLM API has generous quota for sustained real usage (Gemini's free tier is 20
  requests/day) — use `AI_PROVIDER=ollama` for unlimited local usage, or a paid tier for production.
- GitHub webhook registration on GitHub's side isn't automated by the app — the manual "trigger
  review" button is the workaround until that's built.
- A repository renamed on GitHub after being connected (e.g. `owner/old-name` → `owner/new-name`)
  will 404 on PR review/reindex until reconnected — the app doesn't follow GitHub's redirect yet.
- SendGrid's free tier caps at 100 emails/day — fine for real use, would need a paid tier only at
  meaningfully higher signup volume.

## Repo layout

```
backend/       Spring Boot API — see backend/README.md
ai-service/    Python FastAPI RAG + agents service — see ai-service/README.md
frontend/      React dashboard — see frontend/README.md
docs/          ARCHITECTURE.md, database.md, rag.md, agents.md, deployment.md
docker-compose.yml
.env.example
```
