# CodePilot Backend

Spring Boot (Java 25, Maven) backend for CodePilot - an AI codebase-intelligence platform. It
owns auth, GitHub repository ingestion, the REST API consumed by the frontend, and orchestrates
calls to the separate Python AI service for RAG Q&A, PR review, and onboarding-doc generation.

## Stack

- Java 25, Spring Boot 3.3 (Web, WebFlux's `WebClient`, Security, Data JPA, Data Redis, Validation, Actuator)
- PostgreSQL (+ `pgvector`/`pgcrypto` extensions) with Flyway migrations
- Redis (response + Q&A answer caching)
- JWT auth (via `jjwt`), BCrypt password hashing
- Lombok

## Running locally

### 1. Prerequisites

- JDK 25+, Maven 3.9+
- PostgreSQL 15+ with the `pgvector` extension available (e.g. the `pgvector/pgvector:pg16` Docker image)
- Redis 7+
- The Python AI service running (or at least reachable) at `AI_SERVICE_URL`

### 2. Start Postgres + Redis (example with plain Docker)

```bash
docker run -d --name codepilot-postgres -p 5432:5432 \
  -e POSTGRES_DB=codepilot -e POSTGRES_USER=codepilot -e POSTGRES_PASSWORD=codepilot \
  pgvector/pgvector:pg16

docker run -d --name codepilot-redis -p 6379:6379 redis:7
```

### 3. Configure environment

All configuration comes from env vars (see `application.yml` for the full list and defaults):

```bash
export SERVER_PORT=8080
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/codepilot
export SPRING_DATASOURCE_USERNAME=codepilot
export SPRING_DATASOURCE_PASSWORD=codepilot
export SPRING_REDIS_HOST=localhost
export SPRING_REDIS_PORT=6379
export JWT_SECRET=change-me-to-a-long-random-string
export JWT_EXPIRATION_MS=86400000
export AI_SERVICE_URL=http://localhost:8000
export APP_ENCRYPTION_KEY=change-me-to-a-long-random-string
export CORS_ALLOWED_ORIGIN=http://localhost:5173
```

All of these have sensible local defaults baked into `application.yml`, so the app will still
boot without setting anything - but you should always override `JWT_SECRET` and
`APP_ENCRYPTION_KEY` outside of local dev.

### 4. Run

```bash
mvn spring-boot:run
```

Flyway runs automatically on startup and creates the schema (`db/migration/V1__init.sql`),
including the `code_chunks` table that the Python AI service reads/writes directly.

Health check: `GET http://localhost:8080/actuator/health`

### 5. Run tests

```bash
mvn test
```

### 6. Docker

```bash
docker build -t codepilot-backend .
docker run -p 8080:8080 --env-file .env codepilot-backend
```

## Package layout

- `config` - Spring `@Configuration` classes: Spring Security + CORS (`SecurityConfig`), the
  `WebClient` beans used to call GitHub and the AI service (`WebClientConfig`), the async executor
  backing background indexing/review jobs (`AsyncConfig`), and the Redis `RedisTemplate`
  (`RedisConfig`).
- `security` - JWT issuing/parsing (`JwtService`), the `OncePerRequestFilter` that authenticates
  requests from the `Authorization` header (`JwtAuthFilter`), the Spring Security
  `UserDetailsService` (`AppUserDetailsService`), and the `UserDetails` principal (`UserPrincipal`).
- `entity` - JPA entities mapping onto the tables in `V1__init.sql` (all except `code_chunks`,
  which is owned by the Python AI service and has no JPA entity here).
- `repository` - Spring Data JPA repository interfaces.
- `dto` - request/response records, split into `auth`, `repo`, `qa`, `review`, `onboarding`,
  `ai` (the exact request/response shapes for the Python AI service's `/index`, `/query`,
  `/review`, `/onboarding` endpoints), `github` (GitHub REST API response shapes), and `error`.
- `service` - business logic:
  - `AuthService` - register/login, password hashing, JWT issuing.
  - `RepositoryService` - repository CRUD + ownership checks + Redis caching of `GET /repositories/{id}`.
  - `IndexingService` - the `@Async` background job that fetches a repo's files from GitHub and
    ships them to the AI service's `/index` endpoint, updating `code_repositories`/`index_jobs`.
  - `GitHubClient` - real GitHub REST API calls (tree, blobs, PR files/diffs) via `WebClient`.
  - `AiServiceClient` - typed client for the Python AI service's four endpoints.
  - `EncryptionService` - AES-256-GCM encryption for GitHub access tokens at rest.
  - `CacheService` - Redis get/put helpers for repository responses and Q&A answers (TTL'd).
  - `QaService`, `ReviewService`, `OnboardingService` - the remaining repo-scoped features.
  - `WebhookService` - verifies GitHub's HMAC signature and reacts to `pull_request` events by
    fetching the diff and requesting an AI review.
- `controller` - REST controllers, one per resource area, matching the API surface below.
- `exception` - `ApiException` (+ `AiServiceException`) and a `@RestControllerAdvice` that turns
  all of them (plus validation errors) into a consistent `{"error": ..., "status": ...}` body.

## API surface

See the top-level project spec for the full contract; in short, everything under `/api` is
JWT-protected except `/api/auth/**` and `/api/webhooks/**` (which is HMAC-verified instead).

## Notes / known simplifications (first-pass skeleton)

- `APP_ENCRYPTION_KEY` is a static, in-process AES key (SHA-256-derived from the env var). In
  production this should be backed by a real KMS with rotation and envelope encryption - see the
  comment on `EncryptionService`.
- Indexing runs on a small in-process thread pool (`AsyncConfig`), not a durable job queue. Good
  enough for a skeleton; a real deployment would want retries/backoff and a persistent queue.
- Webhook signature verification tries every `code_repositories` row matching the payload's
  owner/repo (in case the same GitHub repo was connected by more than one user) and accepts the
  first whose stored `webhook_secret` produces a matching HMAC.
