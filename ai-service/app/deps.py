"""
FastAPI dependency getters. Shared singletons (VectorStore's async engine,
the embedding provider, the LLM client) are created once at app startup and
stashed on `app.state` (see main.py's lifespan) -- these functions just pull
them out for route handlers via `Depends(...)`.
"""
from __future__ import annotations

from fastapi import Request

from app.services.embeddings import EmbeddingProvider
from app.services.llm import LLMClient
from app.services.vector_store import VectorStore


def get_vector_store(request: Request) -> VectorStore:
    return request.app.state.vector_store


def get_embedding_provider(request: Request) -> EmbeddingProvider:
    return request.app.state.embedding_provider


def get_llm_client(request: Request) -> LLMClient:
    return request.app.state.llm_client
