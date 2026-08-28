# RAG pipeline

Everything below lives in `ai-service/` (Python/FastAPI). The Spring Boot backend never talks to
Postgres' vector column or an LLM directly — it calls the ai-service over HTTP and persists the
result.

## End-to-end flow

```
User question
    │
    ▼
Backend: POST /api/repositories/{id}/ask
    │  Redis cache check first (CacheService) — an identical question on the
    │  same repo within a 10-minute TTL returns the cached answer with no
    │  re-embed, no re-generate round trip.
    ▼
ai-service: POST /query { repositoryId, question, topK }
    │
    ├─ 1. embed the question (same provider/model used at index time)
    ├─ 2. cosine-similarity search top-K chunks in pgvector, scoped to this repository
    ├─ 3. build a prompt containing ONLY the retrieved chunks as context
    ├─ 4. call the LLM with a system prompt that forbids answering outside that context
    └─ 5. return { answer, citations[] }
    │
    ▼
Backend: persist to qa_history, cache the response, return to frontend
    │
    ▼
Frontend: renders the answer with clickable file:line citations
```

## Chunking

`app/services/chunking.py`. Rather than naive fixed-size character windows, files are split along
semantic boundaries so each chunk is a coherent, independently-retrievable unit:

- **Java/Kotlin/C#-family**: class/interface/enum declarations and method signatures (regex-based
  boundary detection, not a full parser — a deliberate scope trade-off, see "Known limitations"
  below).
- **JavaScript/TypeScript/JSX/TSX**: function declarations, classes, exported components, arrow
  functions assigned to `const`.
- **Markdown**: heading boundaries (`#`, `##`, ...), so each chunk is one section.
- **Everything else** (Python, YAML, JSON, plain text, and any language without a heuristic):
  an overlapping sliding window (`FALLBACK_WINDOW = 80` lines, `FALLBACK_OVERLAP = 10` lines) so
  no content falls between chunk boundaries.

Any chunk — from either path — that ends up longer than `MAX_CHUNK_LINES = 150` is further split.
Every chunk carries its file path, language, and 1-indexed `[start_line, end_line]` range as
metadata, which is exactly what gets stored in `code_chunks` and later surfaced as citations.

Chunk size/overlap are the module-level constants above; making them env-configurable rather than
hardcoded is a natural next step (currently a deliberate simplicity trade-off for a project this
size — see the "Suggested next steps" in the root README).

## Incremental indexing (hash diffing)

Before chunking or embedding anything, `/index` diffs the incoming files' SHA-256 content hashes
against the `indexed_files` table (see [`docs/database.md`](database.md#indexed_files---owned-by-ai-service)):

- **Unchanged** files (hash matches) are skipped entirely — no re-chunk, no re-embed, no DB writes.
- **New or changed** files are chunked and embedded, their old chunks (if any) deleted, new chunks
  inserted, and their hash upserted.
- **Removed** files (present in `indexed_files` but absent from the incoming file list) have their
  chunks and hash row deleted.

This is what makes webhook-triggered re-indexing on every `push` to the default branch cheap: a
push touching 3 files only re-embeds those 3 files, regardless of repository size.

## Embeddings

`app/services/embeddings.py`, selected via `EMBEDDING_PROVIDER` env var — the RAG pipeline talks
to an `EmbeddingProvider` abstract interface, so swapping providers requires no code changes
anywhere else in the pipeline:

- **`local`** (default): a hashing-based bag-of-words embedding. No API key, no network call, runs
  fully offline — a legitimate, explainable baseline rather than a stub, and the reason
  `docker compose up` works with zero configuration.
- **`openai`**: real semantic embeddings via `OPENAI_API_KEY` + `OPENAI_EMBEDDING_MODEL`
  (`text-embedding-3-small` by default).

Both are fixed at **1536 dimensions** regardless of provider, so the `code_chunks.embedding`
column never needs a schema migration when switching providers — only re-indexing.

Requests are batched (`EMBED_BATCH_SIZE = 64` in `app/routers/index.py`) to reduce round trips
against real embedding APIs.

## Vector search

`VectorStore.similarity_search` (`app/services/vector_store.py`) runs pgvector's cosine-distance
operator directly in the `ORDER BY` clause, scoped to `repository_id`, `LIMIT top_k`. `top_k` is
caller-configurable per request (`QueryRequest.top_k`, clamped to `[1, 50]` in the router) rather
than a single global constant.

There's no separate relevance-threshold cutoff today — the LLM itself is instructed to say "not
enough information" if the retrieved chunks don't actually answer the question (see below), which
is a softer, prompt-level filter rather than a hard distance cutoff before the chunks reach the
model. A hard threshold + reranking step is a natural addition if retrieval precision needs
tuning — see the root README's suggested next steps.

## Prompt construction & hallucination mitigation

`app/services/rag.py`. The system prompt (`QUERY_SYSTEM_PROMPT`) is explicit and enforced, not
aspirational:

```
You are CodePilot, a codebase Q&A assistant. You answer questions about a specific software
repository using ONLY the provided code context -- never your general knowledge or assumptions
about what the code "probably" does.

Rules:
- Base your answer strictly on the context chunks given below. Do not invent file names,
  functions, or behavior that isn't shown.
- When you reference code, cite the specific file path and line range it came from, in the
  form (path:startLine-endLine).
- If the provided context does not contain enough information to answer the question, respond
  with exactly: "I don't have enough information in the indexed code to answer that." Do not
  guess.
- Be concise and technical.
```

Every retrieved chunk is labeled with its file path and line range in the user prompt before the
question is appended, so the model always has the citation metadata in front of it when
generating.

**Citations returned to the API caller are derived from the retrieved chunks themselves — not
parsed out of the model's free-text answer.** That's a deliberate correctness choice: even if the
model's inline citation text is slightly off, the `sources[]` array in the response is always
accurate, because it's built from what was actually retrieved, not from what the model claims it
used.

## Repository content is untrusted input

Retrieved chunks are repository *content*, inserted into the prompt as data, never as
instructions — the system prompt above is the only place instructions come from. A comment or
README containing text like "ignore previous instructions" is just more text to answer questions
about, not a command the model executes, because nothing in the pipeline re-interprets chunk
content as a role/instruction message. See [`docs/agents.md`](agents.md#repository-content-is-untrusted-input)
for the same principle applied to the PR-review agents, which face a higher-stakes version of this
(reviewing a PR that could itself be adversarial).

## Response shape

```json
{
  "answer": "Authentication is handled in SecurityConfig...",
  "citations": [
    { "filePath": "src/auth/SecurityConfig.java", "startLine": 32, "endLine": 67, "snippet": "..." }
  ],
  "chunksRetrieved": 6
}
```

## Known limitations (honest, not hidden)

- Boundary detection is regex-based, not a real parser — it handles the common cases well but
  isn't immune to unusual formatting.
- No relevance-threshold cutoff or reranking step yet (see above).
- No retrieval-quality evaluation harness yet — the single highest-value next addition per
  `docs/ARCHITECTURE.md`.
