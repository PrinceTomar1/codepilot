# CodePilot — Architecture & Design Decisions

This doc exists for one reason: when an interviewer asks "why did you do X," you should be able to
point here and then explain it in your own words. Read it, don't just paste it into a resume.

## 1. The end-to-end flow

### Connecting and indexing a repo

```
User submits {owner, repo, GitHub token}
        │
        ▼
Backend: POST /api/repositories
        │  - creates code_repositories row (status=PENDING)
        │  - encrypts + stores the GitHub token (AES-256-GCM)
        │  - generates a per-repo webhook secret
        │  - returns immediately, kicks off indexing on a background thread
        ▼
GitHubClient walks the repo's git tree, fetches file contents
(binary/image/node_modules/.git/build/dist filtered out, size- and count-capped)
        │
        ▼
Backend: POST ai-service /index  { repositoryId, files[] }
        │
        ▼
ai-service: hash every file's content (SHA-256), diff against the
            `indexed_files` table for this repo → chunk + embed ONLY
            new/changed files → delete chunks+hashes for changed/removed
            files → upsert new chunks and hashes. Unchanged files are
            never re-chunked or re-embedded.
        │
        ▼
Backend: mark repository INDEXED (or FAILED with the error captured)
```

### Asking a question (RAG)

```
User question
    │
    ▼
Backend: POST /api/repositories/{id}/ask  (Redis cache check first — same
         question on the same repo within 10 min returns the cached answer)
    │
    ▼
ai-service: embed the question → cosine-similarity search top-K chunks in
            pgvector → build a prompt that includes ONLY those chunks as
            context, instructs the model to answer only from that context
            and to say so explicitly when the context doesn't cover the
            question → call Claude → return {answer, citations[]}
    │
    ▼
Backend: persist to qa_history, cache the response, return to frontend
```

### PR review (the agentic pipeline)

```
GitHub PR opened/updated
        │
        ▼
     Webhook  ──► Backend verifies X-Hub-Signature-256 (HMAC, per-repo secret)
        │
        ▼
Backend fetches changed files + diffs, creates a pull_requests row
        │
        ▼
Backend: POST ai-service /review { pullRequestId, files[] }
        │
        ▼
        ┌─────────────────────────────────────────────┐
        │  asyncio.gather — four agents run CONCURRENTLY │
        │                                                │
        │  SecurityAgent      → security findings        │
        │  BugDetectionAgent  → bug findings              │
        │  TestCoverageAgent  → missing-test findings      │
        │  CodeQualityAgent   → code-smell + perf findings │
        └─────────────────────────────────────────────┘
        │
        ▼
ReviewOrchestrator merges all four + generates a one-paragraph summary
        │
        ▼
Backend persists review_reports, frontend renders it grouped by category
```

## 2. Key decisions, and how to defend them

**Why RAG instead of just stuffing the whole repo into a long context window?**
Cost and precision. A large repo doesn't fit in any context window cheaply or reliably, and even
when it technically fits, retrieval quality degrades as irrelevant context grows ("lost in the
middle"). RAG retrieves only the chunks relevant to the specific question, which is both cheaper
per query and keeps the model's attention on what actually matters. The trade-off is that RAG is
only as good as the retrieval step — hence the eval harness in the "what's missing" section below.

**Why pgvector instead of a dedicated vector database (Pinecone, Weaviate, etc.)?**
One fewer moving part. The project already needs Postgres for its relational data (users, repos,
reviews); pgvector adds vector similarity search to the same database instead of standing up and
operating a second stateful system. At this project's scale that's the right trade-off. The honest
answer to "would this hold at scale" is: pgvector scales well into the millions of vectors with an
IVFFlat or HNSW index, but past a few hundred million vectors, or if you need sub-10ms p99 latency
under heavy concurrent load, a dedicated vector store's specialized indexing would likely win —
that's a "when I'd revisit this" answer, not a hole in the design.

**Why two embedding providers (local hashing vs. OpenAI)?**
So the project runs with zero external dependencies out of the box (useful for anyone cloning it,
and for CI), while still supporting real semantic embeddings when an API key is available. The
local provider is an honest feature-hashing bag-of-words embedding, not a placeholder — it's a
legitimate, explainable baseline, and swapping providers requires no schema change because both are
fixed at 1536 dimensions. The retrieval-quality difference between the two is exactly the kind of
thing worth measuring and quoting a number for (see "what's missing," below).

**How is hallucination mitigated?**
The query prompt explicitly restricts the model to the retrieved chunks and instructs it to say it
doesn't have enough information rather than guessing when the context doesn't cover the question.
That's a real, testable constraint — the honest next step is to measure how often it's actually
followed (an eval set of questions with known "not in this repo" answers, checking the model
declines rather than confabulates).

**Why four separate agents instead of one prompt that checks everything?**
Each agent has a narrow, focused system prompt (just security, just bugs, etc.), which produces
more consistent, higher-recall findings per category than asking one model to juggle five concerns
in a single pass — and running them concurrently with `asyncio.gather` means the total latency is
roughly the slowest single agent, not the sum of all four.

**How are duplicate webhook deliveries handled?**
Two layers. First, GitHub sends a unique `X-GitHub-Delivery` header per delivery attempt (including
redeliveries of the same logical event); the webhook handler does a Redis `SETNX` on that ID with a
24h TTL, after signature verification succeeds, so a true redelivery is dropped before any GitHub
API calls or AI review work happens at all — not just deduplicated after the fact. Second, as a
belt-and-suspenders backstop, `pull_requests` also has a unique constraint on
`(repository_id, github_pr_number)` and the handler upserts rather than blindly inserting, so even
without the delivery-ID check a re-delivered "opened" event updates the existing row instead of
creating a duplicate. `push` events reuse the same dedup path and trigger a re-index of the default
branch — cheap thanks to the file-hash diffing described below, since a push touching a few files
only re-embeds those files, not the whole repo.

**Why Redis, specifically?**
Two real uses, not decoration: caching repeated identical questions (same repo + same question
within a TTL window returns instantly without a re-embed + re-generate round trip), and caching the
repository-status endpoint the frontend polls while indexing is in progress, so that polling
doesn't hit Postgres on every tick.

**How would this scale to 100,000 repositories?**
Honest answer for this skeleton: it wouldn't, without several changes — indexing needs to move off
a synchronous background thread and onto a real job queue (Kafka/SQS/similar) with worker
autoscaling; embedding calls need batching and backpressure; pgvector needs a proper ANN index
(IVFFlat/HNSW) and likely partitioning by repository; the webhook handler needs to be idempotent
under concurrent delivery (a real queue + dedup key, not just a unique constraint); and read paths
need caching pushed further up. That's the "what I'd do next" list, which is a stronger interview
answer than pretending the skeleton already handles it.

## 3. What's intentionally not done yet (say this proactively in interviews — it reads as maturity, not gaps)

- **No retrieval-quality evaluation harness yet.** The single highest-value addition: a held-out
  set of question → correct-file-reference pairs, scored for precision/recall/MRR, run against both
  embedding providers so there's a real number to quote ("switching to OpenAI embeddings improved
  retrieval precision from X% to Y%").
- **No prompt-injection defense for RAG-retrieved content.** A malicious README or code comment
  could contain text trying to manipulate the agent. Worth adding: instruction/data separation in
  the prompt, and sanitizing retrieved chunks before they reach the model.
- **Webhook/indexing processing is synchronous-ish (a background thread, not a queue).** Fine for a
  demo, the first thing to change before "real" scale.
- **No load testing yet.** Would want p50/p95/p99 latency numbers under concurrent load before
  claiming any performance story.
- **The local embedding provider trades semantic quality for zero dependencies.** Good default for
  a portfolio project that has to "just run"; not what you'd ship in production.

## 4. Suggested build order if you're extending this yourself

**Month 1 (foundation):** get `docker compose up` fully working, register/login, connect a real
small repo end to end, confirm indexing and one Q&A round-trip work.

**Month 2 (AI depth):** build the retrieval eval harness, try the OpenAI embedding provider and
compare, tune chunking, harden the review agents' prompts against a few real PRs, add the
architecture-graph generation as a stretch feature.

**Month 3 (production polish):** move indexing/webhooks onto a queue, add structured logging and
basic metrics, write tests for the agent pipeline, deploy (Render/Railway/small VPS), record a
demo video, write the resume bullets from what actually got built and measured — not from this doc.
