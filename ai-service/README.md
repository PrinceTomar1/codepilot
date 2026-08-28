# CodePilot AI/RAG Service

The AI codebase-intelligence backend for CodePilot: indexes a repository's
source into a pgvector table, answers questions about it with retrieval-
augmented generation, runs a concurrent multi-agent PR review, and generates
onboarding documentation. Built with FastAPI + SQLAlchemy(async)/psycopg +
pgvector + httpx + the Anthropic SDK.

## Running locally

1. Python 3.11+. Create a virtualenv and install dependencies:

   ```bash
   python -m venv .venv && source .venv/bin/activate
   pip install -r requirements.txt
   ```

2. Copy `.env.example` to `.env` and adjust as needed. With no keys set at
   all, the service still starts and `/health` and `/index` work (using the
   zero-dependency local embedding provider); `/query`, `/review`, and
   `/onboarding` need `ANTHROPIC_API_KEY` because they call Claude to
   generate text.

   ```bash
   cp .env.example .env
   ```

3. This service does **not** create the `code_chunks` table -- it's created
   by the Spring Boot backend's Flyway migration (pgvector extension +
   `code_chunks(id, repository_id, file_path, language, start_line, end_line,
   content, embedding vector(1536), created_at)`). Point `DATABASE_URL` at
   that same Postgres instance.

4. Run the app:

   ```bash
   uvicorn main:app --host 0.0.0.0 --port 8000 --reload
   ```

5. Check it's alive:

   ```bash
   curl http://localhost:8000/health
   # {"status":"ok"}
   ```

### Docker

```bash
docker build -t codepilot-ai-service .
docker run --rm -p 8000:8000 --env-file .env codepilot-ai-service
```

## Endpoint contract

All request/response field names below are camelCase on the wire (the
service is consumed by a Spring Boot backend).

### `GET /health`
`{"status": "ok"}`

### `POST /index`
Chunks and (re-)embeds every file of a repository, replacing any previously
indexed chunks for that `repositoryId`.

```json
// request
{ "repositoryId": "<uuid>", "files": [ { "path": "src/Foo.java", "language": "java", "content": "..." } ] }
// response
{ "repositoryId": "<uuid>", "filesIndexed": 12, "chunksCreated": 143, "status": "COMPLETED" }
```

### `POST /query`
Retrieval-augmented Q&A over an indexed repository. The question is embedded
with the same provider used at index time, the top-K nearest chunks (cosine
distance via pgvector's `<=>` operator) are retrieved, and the model is
instructed to answer **only** from that context, cite `file:line` ranges,
and explicitly say `"I don't have enough information in the indexed code to
answer that."` when the context doesn't cover the question (implemented as
a hard instruction plus a hardcoded early-return when zero chunks are
retrieved at all -- see `app/services/rag.py`).

```json
// request
{ "repositoryId": "<uuid>", "question": "How does auth work?", "topK": 8 }
// response
{ "answer": "...", "citations": [ { "filePath": "...", "startLine": 1, "endLine": 20, "snippet": "..." } ], "chunksRetrieved": 8 }
```

### `POST /review`
Runs four independent review agents (`SecurityAgent`, `BugDetectionAgent`,
`TestCoverageAgent`, `CodeQualityAgent`) **concurrently** via
`asyncio.gather`, each with its own system prompt and its own LLM call, then
merges their structured findings plus an LLM-written summary paragraph.

```json
// request
{ "pullRequestId": "<uuid>", "files": [ { "path": "...", "diff": "...", "fullContent": "..." } ] }
// response
{
  "summary": "...",
  "findings": {
    "bugs": [ { "file": "...", "line": 42, "severity": "high", "description": "...", "suggestion": "..." } ],
    "security": [...],
    "codeSmells": [...],
    "missingTests": [...],
    "performance": [...]
  }
}
```

### `POST /onboarding`
Samples up to ~2 chunks per indexed file (prioritizing likely entry points
like `main`/`App`/config/build files), and asks the model to produce an
architecture overview, module list, setup instructions, data flow
description, and a "read first" file list.

```json
// request
{ "repositoryId": "<uuid>" }
// response
{ "architectureOverview": "...", "importantModules": [{"path":"...","description":"..."}], "setupInstructions": "...", "dataFlow": "...", "readFirst": ["..."] }
```

All endpoints return JSON error bodies (`{"error": "..."}`) with proper HTTP
status codes on failure -- unhandled exceptions never reach the client as
raw tracebacks (see the global exception handlers in `main.py`).

## Embedding provider tradeoff

Both providers produce fixed **1536-dimension** vectors so the pgvector
schema never needs to change regardless of which is active
(`EMBEDDING_PROVIDER=local|openai`).

- **`local` (default, zero API keys/dependencies beyond numpy)** --
  `LocalHashEmbeddingProvider` is a hashing-trick bag-of-words embedding: it
  tokenizes text (splitting camelCase/snake_case identifiers so code tokens
  like `getUserById` become meaningful sub-tokens), hashes each token into
  one of 1536 buckets with a sign trick to reduce collision bias, accumulates
  into a vector, and L2-normalizes. This is a real, explainable baseline
  (essentially `sklearn`'s `HashingVectorizer` idea) that captures rough
  token/identifier overlap between a query and code -- but it has no real
  semantic understanding, so recall on paraphrased questions will be weaker
  than a learned embedding model.
- **`openai`** -- calls OpenAI's `text-embedding-3-small` (natively
  1536-dim) via `httpx`, batched. This is the intended production path once
  an `OPENAI_API_KEY` is available; it gives much better semantic retrieval
  quality at the cost of an external dependency/API key.

## Chunking

Rather than fixed-size windows, chunking looks for semantic boundaries:

- **Java/Kotlin/C#** -- class/interface/enum declarations and method
  signatures (regex-based, annotation-aware).
- **JS/TS/JSX/TSX** -- function declarations, class declarations, and
  arrow-function/`function` expression consts (covers typical React
  components/hooks).
- **Markdown** -- split by heading (`#`..`######`).
- **Everything else, or a language heuristic that finds no boundaries** --
  an 80-line sliding window with 10-line overlap.

Any resulting chunk over ~150 lines (e.g. one huge function) is further
split into <=150-line pieces. See `app/services/chunking.py`.

## Project layout

```
main.py                          FastAPI app, lifespan, global exception handlers
app/config.py                    pydantic-settings config (.env driven)
app/deps.py                      FastAPI Depends() getters for shared singletons
app/models/schemas.py            pydantic request/response models (exact API contract)
app/routers/{index,query,review,onboarding,health}.py
app/services/chunking.py         language-aware-ish chunking
app/services/embeddings.py       EmbeddingProvider interface + local/OpenAI impls
app/services/vector_store.py     SQLAlchemy async + pgvector access to code_chunks
app/services/llm.py              Anthropic Claude client wrapper
app/services/rag.py              query prompt construction + onboarding prompt
app/agents/base_agent.py         shared agent JSON-output parsing
app/agents/{security,bug,test,quality}_agent.py
app/agents/review_orchestrator.py  concurrent agent execution + merge + summary
tests/                           pytest unit tests (chunking, local embeddings)
```

## Tests

```bash
pytest
```
