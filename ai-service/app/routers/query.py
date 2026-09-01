from __future__ import annotations

import asyncio
import logging
import uuid

from fastapi import APIRouter, Depends, HTTPException

from app.deps import get_embedding_provider, get_llm_client, get_vector_store
from app.models.schemas import QueryRequest, QueryResponse
from app.services.embeddings import EmbeddingProvider
from app.services.llm import LLMClient, LLMNotConfiguredError, LLMRateLimitedError
from app.services.rag import (
    adaptive_top_k,
    answer_question,
    extract_keywords,
    merge_retrieved_chunks,
    prioritize_representative_chunks,
)
from app.services.vector_store import VectorStore

logger = logging.getLogger("codepilot.query")

router = APIRouter()


@router.post("/query", response_model=QueryResponse)
async def query_repository(
    body: QueryRequest,
    store: VectorStore = Depends(get_vector_store),
    embedder: EmbeddingProvider = Depends(get_embedding_provider),
    llm: LLMClient = Depends(get_llm_client),
) -> QueryResponse:
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

    try:
        answer, citations = await answer_question(
            llm, body.question, chunks, body.history, has_keyword_match=bool(keyword_chunks),
        )
    except LLMNotConfiguredError as exc:
        raise HTTPException(status_code=503, detail=str(exc))
    except LLMRateLimitedError as exc:
        raise HTTPException(status_code=429, detail=str(exc))

    return QueryResponse(answer=answer, citations=citations, chunksRetrieved=len(chunks))
