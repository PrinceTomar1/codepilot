# Deployment

## Local development (Docker Compose)

The documented path. From the repo root:

```bash
cp .env.example .env        # fill in ANTHROPIC_API_KEY at minimum for Q&A/review/onboarding
docker compose up --build
```

This starts, in dependency order (via Compose `healthcheck` + `depends_on: condition: service_healthy`):

1. **postgres** (`pgvector/pgvector:pg16`) — health-checked with `pg_isready`
2. **redis** (`redis:7-alpine`) — health-checked with `redis-cli ping`
3. **mailpit** — local SMTP catcher for verification emails (SMTP on `1025`, web UI on `8025`); no
   health check declared (it's a dev-only convenience, not on the critical path)
4. **ai-service** — waits for postgres healthy; health-checked against `GET /health`
5. **backend** — waits for postgres, redis, ai-service all healthy (and mailpit started); Flyway
   runs migrations on boot; health-checked against `GET /actuator/health`
6. **frontend** — waits for backend healthy; Vite dev server, health-checked against `GET /`

Every image (`backend/Dockerfile`, `ai-service/Dockerfile`, `frontend/Dockerfile`) declares its
own `HEALTHCHECK` so `docker compose ps` and `depends_on: condition: service_healthy` reflect
real readiness, not just "the process started."

- Frontend: http://localhost:5173
- Backend API: http://localhost:8080/api (health: `/actuator/health`, API docs: `/swagger-ui/index.html`)
- AI service: http://localhost:8000 (health: `/health`)
- Mailpit web UI: http://localhost:8025 (view verification emails without a real mail provider)

## Environment variables

See `.env.example` for the full annotated list. The ones that actually gate functionality:

| Variable | Effect if unset |
|---|---|
| `AI_PROVIDER` (+ matching key) | `anthropic` (default), `gemini`, or `ollama` — a model running entirely on your own infrastructure, no external API/key/quota at all (self-host the `ollama/ollama` image; see the `ollama` service in `docker-compose.yml` — pull a model into it once with `docker compose exec ollama ollama pull qwen2.5:7b-instruct`). Without a working provider, indexing/retrieval still work (local embedding provider), but Q&A/PR review/onboarding return a clean `503` instead of a raw failure. |
| `EMBEDDING_PROVIDER` / `OPENAI_API_KEY` | Defaults to `local` (hashing-based, zero external calls). Set to `openai` + a key for real semantic embeddings — no schema change needed, both are fixed at 1536 dimensions. |
| `JWT_SECRET`, `APP_ENCRYPTION_KEY` | Default to insecure dev placeholders (backend logs make this obvious). **Must** be changed before deploying anywhere real — `APP_ENCRYPTION_KEY` in particular is what protects stored GitHub tokens at rest. |
| `MAIL_PROVIDER` (+ matching config) | `smtp` (default, Mailpit in Compose) or `sendgrid`. **Use `sendgrid` in production** — several PaaS hosts (Railway confirmed) block outbound SMTP entirely on their free tier, so `smtp` there means verification emails silently never arrive. `SENDGRID_FROM_ADDRESS` must be exactly the address verified as a Single Sender at app.sendgrid.com/settings/sender_auth (no domain purchase needed — verifying one address you already own is enough, and free tier covers 100 emails/day). |
| `RATE_LIMIT_*` | Sensible defaults (20 AI calls/min, 10 repo-connects/hour, 120 webhook deliveries/min, all per identity). Override per-bucket via env if needed; set `RATE_LIMIT_ENABLED=false` to disable entirely (not recommended in production). |

## Database & migrations

Flyway runs automatically on backend startup (`spring.flyway.enabled=true`,
`baseline-on-migrate=true`) — there's no separate migration step to remember. Migrations are
plain versioned SQL in `backend/src/main/resources/db/migration/`; see
[`docs/database.md`](database.md) for what each one added. For a production deploy, the
recommended pattern is: run migrations as an explicit step before rolling out new backend
instances (rather than relying on "first replica to start wins"), since Flyway's own locking
handles concurrent-migration safety but a dedicated migration step keeps schema changes visible
in your deploy pipeline.

`pgvector` must be available on whatever Postgres you point at — the `pgvector/pgvector:pg16`
image is used locally specifically because it ships the extension; a managed Postgres provider
needs to support installing `pgvector` (most modern ones do — Supabase, Neon, RDS with the
extension enabled, etc.).

## GitHub integration setup

Two ways to connect a repository: paste a personal access token (`repo` read + `webhook` scopes)
directly, or sign in with **"Continue with GitHub" OAuth** and pick from your own repos (or type
any owner/repo — a GitHub token authenticates the *caller*, not a claim of ownership, so it works
for public repos and ones you collaborate on regardless of who owns them). OAuth is optional:
leave `GITHUB_CLIENT_ID`/`GITHUB_CLIENT_SECRET` blank to disable it and fall back to PAT-only.
Register an OAuth App at [github.com/settings/developers](https://github.com/settings/developers)
with callback URL `https://<your-backend-domain>/api/auth/github/callback` (or
`GITHUB_OAUTH_REDIRECT_URI` if set to something else).

**Webhooks**: `WebhookService` generates a per-repository secret at connect time and verifies
every inbound delivery's `X-Hub-Signature-256` HMAC against it (constant-time comparison). For a
real deployment, register the webhook against `https://<your-backend-domain>/api/webhooks/github`
for the `pull_request` and `push` events — the backend needs to be reachable from GitHub's
infrastructure, so a local `docker compose up` deployment needs a tunnel (ngrok or similar) to
actually receive webhook deliveries during development.

## Production deployment sketch

This is a skeleton, not a hardened production system — see `docs/ARCHITECTURE.md` for the honest
"what's missing" list. A reasonable path to a real deployment:

1. **Database**: managed Postgres with `pgvector` enabled (see above). Run Flyway migrations as
   an explicit deploy step.
2. **Redis**: managed Redis (used for caching, webhook idempotency, and rate limiting — not
   optional infrastructure, all three degrade gracefully if it's briefly unavailable but shouldn't
   be skipped).
3. **ai-service**: containerize and deploy behind the backend only (it should not be
   publicly reachable — the backend is the only intended caller). Set real `ANTHROPIC_API_KEY`
   and (optionally) `EMBEDDING_PROVIDER=openai` + `OPENAI_API_KEY`.
4. **backend**: containerize, set real `JWT_SECRET`/`APP_ENCRYPTION_KEY` (long random values —
   rotating `APP_ENCRYPTION_KEY` after tokens are already encrypted with the old one requires a
   re-encryption migration, so generate it deliberately once), real SMTP credentials, and
   `CORS_ALLOWED_ORIGIN`/`FRONTEND_URL` pointed at your actual frontend domain.
5. **frontend**: `frontend/Dockerfile` is a real production multi-stage build — `npm run build` in
   a `node:20-alpine` stage, then the static `dist/` output served by `nginx:alpine` (SPA routing
   + long-cache headers on hashed assets via `frontend/nginx.conf`). `VITE_API_BASE_URL` is
   compiled into the bundle at *build* time (Vite env vars aren't read at container runtime), so
   pass it as a Docker build arg, not a runtime environment variable — `docker-compose.yml` already
   does this correctly.
6. Platforms like Render/Railway/Fly.io or a small VPS with Docker Compose are all reasonable for
   this project's scale; see `docs/ARCHITECTURE.md`'s "how would this scale" section for what
   would need to change before a much larger deployment (real job queue instead of an in-process
   `@Async` executor, proper ANN indexing/partitioning for pgvector, etc.).

## Production deployment: Railway (all services)

Concrete steps for the actual chosen path — backend, ai-service, frontend, Postgres, and Redis
all run as Railway services in the same project/private network. The frontend is a static Vite
build served by nginx inside its own container (see `frontend/Dockerfile`), not a separate
static-hosting platform. Do these in order, since later steps need URLs/values produced by
earlier ones.

### 1. Database (Postgres + pgvector) and Redis

In a new Railway project:

- Add Postgres using Railway's **pgvector-enabled template** (search "pgvector" in the Railway
  template marketplace, or deploy https://railway.com/deploy/3jJFCA) — **not** the plain default
  "Add PostgreSQL" button. `V1__init.sql` runs `CREATE EXTENSION IF NOT EXISTS vector;` on first
  boot, which fails the whole backend startup if the extension isn't physically available on the
  Postgres image.
- Add a standard Redis service (no special template needed).
- Note both services' connection details — Railway exposes them as reference variables
  (`${{Postgres.DATABASE_URL}}`, `${{Redis.REDIS_URL}}` etc.) you can point other services at
  without copy-pasting.

### 2. ai-service

New Railway service → deploy from this GitHub repo, root directory `ai-service` (Railway
auto-detects and builds `ai-service/Dockerfile`). Set these environment variables:

| Variable | Value |
|---|---|
| `DATABASE_URL` | Reference to the Postgres service (`postgresql://user:pass@host:port/db`) |
| `EMBEDDING_PROVIDER` | `local` (or `openai` + `OPENAI_API_KEY` for better retrieval quality) |
| `AI_PROVIDER` | `gemini` |
| `GEMINI_API_KEY` | your key (billing-enabled project, per the quota discussion above) |
| `GEMINI_MODEL` | `gemini-3.6-flash` |

Leave it on Railway's **private networking** rather than generating a public domain for it — per
`docs/ARCHITECTURE.md`, the backend is the only intended caller. Note the private hostname
Railway assigns (e.g. `ai-service.railway.internal`) for the next step.

### 3. backend

New Railway service → deploy from this repo, root directory `backend` (builds `backend/Dockerfile`,
which now respects Railway's injected `$PORT` — see the Dockerfile's `ENTRYPOINT`). Set:

| Variable | Value |
|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://<PGHOST>:<PGPORT>/<PGDATABASE>` -- build this from Railway's individual `PGHOST`/`PGPORT`/`PGDATABASE` reference variables. **Not** Railway's combined `DATABASE_URL` value directly -- that's a bare `postgresql://` URI, and Spring's JDBC driver requires the `jdbc:` scheme prefix, so pasting it as-is fails to parse. |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | `PGUSER` / `PGPASSWORD` from the Postgres service, as separate values |
| `SPRING_REDIS_HOST` / `SPRING_REDIS_PORT` / `SPRING_REDIS_PASSWORD` | From the Redis service -- Railway's managed Redis requires auth, so the password variable matters here (`application.yml` reads it) |
| `AI_SERVICE_URL` | `http://ai-service.railway.internal:8000` (the private hostname from step 2) |
| `JWT_SECRET` | a fresh long random value — **do not reuse the local dev one** |
| `APP_ENCRYPTION_KEY` | same — fresh value, generated once and never rotated casually (rotating after tokens are already encrypted needs a re-encryption migration) |
| `MAIL_PROVIDER` | `sendgrid` -- Railway blocks outbound SMTP (confirmed: both port 587 and 465 hang indefinitely until they time out), so plain SMTP credentials will never deliver here regardless of provider |
| `SENDGRID_API_KEY` / `SENDGRID_FROM_ADDRESS` | An API key from a SendGrid account, and the exact address verified as its Single Sender (Settings → Sender Authentication → Verify a Single Sender -- no domain purchase needed) |
| `CORS_ALLOWED_ORIGIN` / `FRONTEND_URL` | placeholder for now (e.g. `https://placeholder.up.railway.app`) — comes back in step 5 |

Generate the network domain Railway offers for this service (Settings → Networking → Generate
Domain) — this is your public backend URL, needed by the frontend next.

### 4. frontend

New Railway service → deploy from this repo, root directory `frontend` (builds
`frontend/Dockerfile`, a multi-stage build: `npm run build` then nginx serves the static output).
Set one build-time variable (Vite compiles `VITE_*` vars into the JS bundle at build time, not
read at container startup, so this must be a Railway *variable*, not something set after the fact):

| Variable | Value |
|---|---|
| `VITE_API_BASE_URL` | `https://<your-backend-railway-domain>/api` |

Generate a public domain for this service too (Settings → Networking → Generate Domain).

One easy-to-miss gotcha: nginx's default config hardcodes `listen 80`, but Railway's edge proxy
connects to a dynamic `$PORT` it assigns per-deployment — the two ports usually only sometimes
match, producing an intermittent `502 Application failed to respond` on an otherwise perfectly
healthy container. `frontend/nginx.conf.template` listens on `${PORT}` and is copied into
nginx's own `/etc/nginx/templates/` directory so the base image's entrypoint substitutes it in
via `envsubst` at container startup (`PORT` defaults to 80 in the Dockerfile for local
`docker run`/compose, where nothing else sets it).

### 5. Close the loop

Go back to the backend's Railway env vars and set `CORS_ALLOWED_ORIGIN` and `FRONTEND_URL` to the
real frontend domain from step 4, then redeploy the backend service so the new values take effect.

### 6. Verify

Register with a real (non-test) email address, confirm the verification email actually arrives,
log in, connect a repository, and ask one question — mindful that AI calls still draw from
whatever Gemini quota/billing is configured.

## Observability

Structured logging via SLF4J (backend) and Python's standard `logging` module (ai-service) is in
place throughout — every service records enough to debug a failed indexing run, a failed webhook,
or a failed LLM call without needing to reproduce it. There's no metrics/tracing stack wired up
yet (Prometheus/OpenTelemetry or similar) — that's a natural addition before treating this as
production-grade at any real scale.
