from __future__ import annotations

import asyncio
import logging
import uuid

from fastapi import APIRouter, Depends, HTTPException

from app.deps import get_embedding_provider, get_vector_store
from app.models.schemas import SearchRequest, SearchResponse
from app.services.embeddings import EmbeddingProvider
from app.services.rag import build_search_results, extract_keywords, merge_retrieved_chunks
from app.services.vector_store import RetrievedChunk, VectorStore

logger = logging.getLogger("codepilot.search")

router = APIRouter()


@router.post("/search", response_model=SearchResponse)
async def search_repository(
    body: SearchRequest,
    store: VectorStore = Depends(get_vector_store),
    embedder: EmbeddingProvider = Depends(get_embedding_provider),
) -> SearchResponse:
    """Direct code search: no LLM call, just retrieval -- for a dedicated search UI distinct
    from the Q&A chat. Reuses the exact same hybrid vector+keyword retrieval as /query (see that
    router for why: a single-method search misses either literal identifier/file matches or
    genuinely conceptual ones)."""
    try:
        repository_id = uuid.UUID(body.repository_id)
    except ValueError:
        raise HTTPException(status_code=400, detail=f"repositoryId is not a valid UUID: {body.repository_id!r}")

    top_k = max(1, min(body.top_k or 20, 50))
    keywords = extract_keywords(body.query)

    try:
        query_embedding = await embedder.embed_one(body.query)
    except Exception as exc:
        logger.exception("Embedding failed during /search")
        raise HTTPException(status_code=502, detail=f"Embedding provider error: {exc}")

    async def _vector_search() -> list[RetrievedChunk]:
        async with store.session() as session:
            return await store.similarity_search(session, repository_id, query_embedding, top_k)

    async def _keyword_search() -> list[RetrievedChunk]:
        async with store.session() as session:
            return await store.keyword_search(session, repository_id, keywords, top_k)

    try:
        vector_chunks, keyword_chunks = await asyncio.gather(_vector_search(), _keyword_search())
    except Exception as exc:
        logger.exception("Database error during /search")
        raise HTTPException(status_code=502, detail=f"Database error: {exc}")

    merged = merge_retrieved_chunks(vector_chunks, keyword_chunks, top_k)
    return SearchResponse(results=build_search_results(merged, keyword_chunks))
