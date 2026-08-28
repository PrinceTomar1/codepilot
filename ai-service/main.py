"""
CodePilot AI/RAG service -- FastAPI entrypoint.

Run locally:
    uvicorn main:app --host 0.0.0.0 --port 8000 --reload
"""
from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

load_dotenv()

from app.config import get_settings
from app.routers import architecture, health, index, onboarding, query, review, search
from app.services.embeddings import get_embedding_provider
from app.services.llm import LLMClient
from app.services.vector_store import VectorStore

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("codepilot")


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    app.state.settings = settings
    app.state.vector_store = VectorStore(settings)
    app.state.embedding_provider = get_embedding_provider(settings)
    app.state.llm_client = LLMClient(settings)

    logger.info(
        "CodePilot AI service starting: embedding_provider=%s, llm_configured=%s",
        settings.EMBEDDING_PROVIDER,
        app.state.llm_client.configured,
    )

    # Best-effort connectivity check; never blocks startup.
    await app.state.vector_store.ensure_vector_extension()

    yield

    await app.state.vector_store.dispose()


app = FastAPI(title="CodePilot AI Service", version="0.1.0", lifespan=lifespan)


# --- Global exception handlers: never leak raw tracebacks to clients. ------

@app.exception_handler(HTTPException)
async def http_exception_handler(request: Request, exc: HTTPException) -> JSONResponse:
    detail = exc.detail if isinstance(exc.detail, str) else str(exc.detail)
    return JSONResponse(status_code=exc.status_code, content={"error": detail})


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    return JSONResponse(status_code=422, content={"error": "Invalid request", "details": exc.errors()})


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    logger.exception("Unhandled exception on %s %s", request.method, request.url.path)
    return JSONResponse(status_code=500, content={"error": "Internal server error"})


# --- Routers -----------------------------------------------------------------

app.include_router(health.router, tags=["health"])
app.include_router(index.router, tags=["index"])
app.include_router(query.router, tags=["query"])
app.include_router(review.router, tags=["review"])
app.include_router(onboarding.router, tags=["onboarding"])
app.include_router(architecture.router, tags=["architecture"])
app.include_router(search.router, tags=["search"])
