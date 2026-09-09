from __future__ import annotations

import asyncio
import json
import logging
import uuid
from collections.abc import AsyncIterator

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse

from app.deps import get_embedding_provider, get_llm_client, get_vector_store
from app.models.schemas import Citation, QueryRequest, QueryResponse
from app.services.embeddings import EmbeddingProvider
from app.services.llm import LLMClient, LLMNotConfiguredError, LLMRateLimitedError
from app.services.rag import (
    adaptive_top_k,
    answer_question,
    answer_question_stream,
    extract_keywords,
    merge_retrieved_chunks,
    prioritize_representative_chunks,
)
from app.services.vector_store import VectorStore

logger = logging.getLogger("codepilot.query")

router = APIRouter()


async def _retrieve_context(
    body: QueryRequest,
    store: VectorStore,
    embedder: EmbeddingProvider,
) -> tuple[list, bool]:
    """Shared retrieval for /query and /query/stream: embed the question, run hybrid (vector +
    keyword) search concurrently, merge, and -- for broad questions -- blend in a few
    representative entry-point/README chunks. Returns (chunks, has_keyword_match). Raises
    HTTPException for a bad UUID / embedding / DB failure, exactly as before."""
    try:
        repository_id = uuid.UUID(body.repository_id)
    except ValueError:
        raise HTTPException(status_code=400, detail=f"repositoryId is not a valid UUID: {body.repository_id!r}")

    requested_top_k = max(1, min(body.top_k or 8, 50))
    top_k = adaptive_top_k(body.question, requested_top_k)
    keywords = extract_keywords(body.question)

    try:
        query_embedding = await embedder.embed_one(body.question)
    except Exception as exc:
        logger.exception("Embedding failed during /query")
        raise HTTPException(status_code=502, detail=f"Embedding provider error: {exc}")

    # Hybrid retrieval: vector similarity and keyword/symbol/filename exact-match search run
    # concurrently (each on its own session -- a single AsyncSession can't run two operations
    # at once), then get merged. Keyword search catches literal identifier/file-name matches
    # that the local hashing-based embedding provider can rank poorly by cosine distance alone.
    async def _vector_search() -> list:
        async with store.session() as session:
            return await store.similarity_search(session, repository_id, query_embedding, top_k)

    async def _keyword_search() -> list:
        async with store.session() as session:
            return await store.keyword_search(session, repository_id, keywords, top_k)

    try:
        vector_chunks, keyword_chunks = await asyncio.gather(_vector_search(), _keyword_search())
    except Exception as exc:
        logger.exception("Database error during /query")
        raise HTTPException(status_code=502, detail=f"Database error: {exc}")

    chunks = merge_retrieved_chunks(vector_chunks, keyword_chunks, top_k)

    # Broad questions ("explain the code") can leave similarity search with NOTHING relevant, not
    # just imperfectly-ranked results -- generic phrasing shares no real vocabulary with actual
    # application code, and the local hashing-based embedding provider has no semantic
    # understanding to bridge that gap. README/entry-point/config files are a real starting point
    # regardless of embedding quality, so blend a few in on top of whatever similarity search found.
    if top_k > requested_top_k:
        try:
            async with store.session() as session:
                candidates = await store.sample_chunks_per_file(session, repository_id, per_file=1)
        except Exception:
            logger.exception("Failed to fetch representative chunks for a broad question")
            candidates = []
        representative = prioritize_representative_chunks(candidates, limit=8)
        existing_keys = {(c.file_path, c.start_line, c.end_line) for c in chunks}
        for c in representative:
            key = (c.file_path, c.start_line, c.end_line)
            if key not in existing_keys:
                chunks.append(c)
                existing_keys.add(key)

    return chunks, bool(keyword_chunks)


@router.post("/query", response_model=QueryResponse)
async def query_repository(
    body: QueryRequest,
    store: VectorStore = Depends(get_vector_store),
    embedder: EmbeddingProvider = Depends(get_embedding_provider),
    llm: LLMClient = Depends(get_llm_client),
) -> QueryResponse:
    chunks, has_keyword_match = await _retrieve_context(body, store, embedder)

    try:
        answer, citations = await answer_question(
            llm, body.question, chunks, body.history, has_keyword_match=has_keyword_match,
        )
    except LLMNotConfiguredError as exc:
        raise HTTPException(status_code=503, detail=str(exc))
    except LLMRateLimitedError as exc:
        raise HTTPException(status_code=429, detail=str(exc))

    return QueryResponse(answer=answer, citations=citations, chunksRetrieved=len(chunks))


def _sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data)}\n\n"


# While the model is still "thinking" (before the first token) or pausing mid-answer, the SSE
# connection would otherwise sit completely idle -- long enough for a proxy or load balancer
# between here and the browser to decide it's dead and cut it, which shows up as an answer that
# stops mid-sentence. A comment frame every few seconds keeps every hop on the path convinced the
# stream is alive without affecting how the client parses it.
_HEARTBEAT_SECONDS = 10.0


@router.post("/query/stream")
async def query_repository_stream(
    body: QueryRequest,
    store: VectorStore = Depends(get_vector_store),
    embedder: EmbeddingProvider = Depends(get_embedding_provider),
    llm: LLMClient = Depends(get_llm_client),
) -> StreamingResponse:
    """Server-Sent Events version of /query -- streams the answer as it's generated.

    Frames:
      event: token  data: {"text": "..."}                         -- incremental answer text
      event: done   data: {"answer","citations","chunksRetrieved"} -- canonical final payload
      event: error  data: {"error","status"}                       -- LLM unavailable mid-stream

    The `done` frame always carries the full, authoritative answer + citations (the token frames
    are purely for progressive rendering), so a consumer can trust `done` even for the cases
    answer_question() handles specially (refusal-retry, general-knowledge, deterministic
    fallback)."""
    if not llm.configured:
        raise HTTPException(
            status_code=503,
            detail=f"LLM not configured: set {'GEMINI_API_KEY' if llm.provider == 'gemini' else 'ANTHROPIC_API_KEY'}",
        )

    chunks, has_keyword_match = await _retrieve_context(body, store, embedder)

    async def generate() -> AsyncIterator[str]:
        answer = ""
        citations: list[Citation] = []

        # Run the RAG generator on its own task feeding a queue, so the heartbeat below can wait on
        # the queue with a timeout WITHOUT cancelling an in-flight LLM request (which is what
        # wrapping `answer_question_stream()` directly in asyncio.wait_for() would do).
        queue: asyncio.Queue = asyncio.Queue()
        _DONE = object()

        async def _pump() -> None:
            try:
                async for item in answer_question_stream(
                    llm, body.question, chunks, body.history, has_keyword_match=has_keyword_match,
                ):
                    await queue.put(("item", item))
            except Exception as exc:  # noqa: BLE001 -- surfaced verbatim to the consumer below
                await queue.put(("error", exc))
            finally:
                await queue.put(("done", _DONE))

        pump_task = asyncio.create_task(_pump())
        try:
            while True:
                try:
                    tag, value = await asyncio.wait_for(queue.get(), timeout=_HEARTBEAT_SECONDS)
                except asyncio.TimeoutError:
                    yield ": keep-alive\n\n"
                    continue

                if tag == "done":
                    break
                if tag == "error":
                    exc = value
                    if isinstance(exc, LLMNotConfiguredError):
                        yield _sse("error", {"error": str(exc), "status": 503})
                    elif isinstance(exc, LLMRateLimitedError):
                        yield _sse("error", {"error": str(exc), "status": 429})
                    else:
                        logger.exception("Unhandled error during /query/stream", exc_info=exc)
                        yield _sse("error", {"error": "Internal server error", "status": 500})
                    return

                kind, payload = value
                if kind == "token":
                    yield _sse("token", {"text": payload})
                else:  # "final"
                    answer, citations = payload
        finally:
            pump_task.cancel()

        yield _sse(
            "done",
            {
                "answer": answer,
                "citations": [c.model_dump(by_alias=True) for c in citations],
                "chunksRetrieved": len(chunks),
            },
        )

    return StreamingResponse(
        generate(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
