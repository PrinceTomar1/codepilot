# CodePilot — Frontend

The frontend for CodePilot, an AI codebase-intelligence platform: GitHub repo
indexing, RAG-based Q&A over a codebase, AI multi-agent PR review, and
auto-generated onboarding docs.

This is one of three independent subprojects (`backend`, `ai-service`,
`frontend`) that make up CodePilot. This app talks to the backend purely over
the REST API described below — it has no knowledge of how the backend or
AI service are implemented.

## Stack

- [Vite](https://vitejs.dev/) + React 18 + TypeScript
- [Tailwind CSS](https://tailwindcss.com/) for styling
- [React Router](https://reactrouter.com/) for routing
- [TanStack Query](https://tanstack.com/query) for server state / caching / polling
- [Axios](https://axios-http.com/) for HTTP, with a single interceptor-based client

## Running locally

```bash
npm install
npm run dev
```

The app starts on **http://localhost:5173**.

By default it talks to `http://localhost:8080/api`. To point it at a
different backend, copy `.env.example` to `.env` and set:

```
VITE_API_BASE_URL=http://localhost:8080/api
```

### Type checking / build

```bash
npx tsc --noEmit   # type check only
npm run build      # type check + production build to dist/
```

### Docker

A dev-mode Dockerfile is included (runs the Vite dev server, not a
production nginx build — see comments in `Dockerfile`):

```bash
docker build -t codepilot-frontend .
docker run -p 5173:5173 --env VITE_API_BASE_URL=http://host.docker.internal:8080/api codepilot-frontend
```

## Auth

Auth is JWT-based. On login/register the token is stored in memory +
`localStorage` and attached to every request as `Authorization: Bearer
<token>` via an axios request interceptor (`src/api/client.ts`). A response
interceptor watches for `401`s, clears the stored session, and the app
redirects to `/login`.

## Page structure

| Route | Description |
| --- | --- |
| `/login` | Sign-in form. |
| `/register` | Account creation form. |
| `/dashboard` | Grid of connected repositories as cards (status badge, indexed date). "Connect repository" opens a modal that takes owner/repo/PAT, kicks off indexing, and polls `GET /repositories/{id}` every few seconds until it reaches `INDEXED`/`FAILED`. |
| `/repositories/:id` | Repo detail with three tabs: **Ask** (chat-style RAG Q&A, with citations rendered as clickable `file:line` badges that preview a code snippet), **PR Reviews** (list of AI reviews → detail view grouping findings by bugs/security/code smells/missing tests/performance, with severity color coding), **Onboarding** (architecture overview, important modules, data flow, setup instructions, "read first" file list — lazily generated server-side, so this tab shows a clear loading state on first load). |

A shared `Sidebar`/`Layout` shows the connected repo list, the signed-in
user, and a logout action on every authenticated page.

## Project layout

```
src/
  api/            axios client + typed API functions + react-query hooks
    client.ts       axios instance, auth token store, 401 interceptor
    auth.ts         login/register
    repositories.ts list/get/create + useRepositories/useRepository/useCreateRepository
    qa.ts           ask + qa-history + useAskQuestion/useQAHistory
    reviews.ts      review list/detail + useReviews/useReviewDetail
    onboarding.ts   onboarding doc + useOnboarding
  components/     RepoCard, ConnectRepoModal, ChatPanel, CitationBadge,
                  ReviewList, ReviewDetail, OnboardingView, StatusBadge,
                  SeverityBadge, Sidebar, Layout, ProtectedRoute
  context/        AuthContext (session state, login/register/logout)
  lib/            small formatting/utility helpers
  pages/          LoginPage, RegisterPage, DashboardPage, RepositoryDetailPage
  types/          TS interfaces mirroring the backend API contract exactly
  App.tsx         route table
  main.tsx        app bootstrap (QueryClientProvider, BrowserRouter, AuthProvider)
```

## API contract this app expects

Base URL: `VITE_API_BASE_URL` (default `http://localhost:8080/api`). All
authenticated requests send `Authorization: Bearer <token>`. Error responses
are expected as JSON: `{"error": "message", "status": <code>}`.

- `POST /auth/register` `{email, password, name}` → `{token, user}`
- `POST /auth/login` `{email, password}` → `{token, user}`
- `GET /repositories` → `Repository[]`
- `POST /repositories` `{githubOwner, githubRepo, accessToken}` → `Repository`
- `GET /repositories/{id}` → `Repository` (poll while `PENDING`/`INDEXING`)
- `POST /repositories/{id}/ask` `{question}` → `{answer, citations, chunksRetrieved}`
- `GET /repositories/{id}/qa-history` → `QAHistoryEntry[]` (most recent first)
- `GET /repositories/{id}/reviews` → `ReviewSummary[]`
- `GET /reviews/{id}` → `ReviewDetail` (findings grouped by category)
- `GET /repositories/{id}/onboarding` → `OnboardingDoc` (lazily generated, can be slow on first call)

See `src/types/*.ts` for the exact shapes.
