from __future__ import annotations

import logging
import uuid

from fastapi import APIRouter, Depends, HTTPException

from app.deps import get_vector_store
from app.models.schemas import ArchitectureEdge, ArchitectureNode, ArchitectureRequest, ArchitectureResponse
from app.services.architecture import build_architecture_graph
from app.services.vector_store import VectorStore

logger = logging.getLogger("codepilot.architecture")

router = APIRouter()


@router.post("/architecture", response_model=ArchitectureResponse)
async def architecture(
    body: ArchitectureRequest,
    store: VectorStore = Depends(get_vector_store),
) -> ArchitectureResponse:
    try:
        repository_id = uuid.UUID(body.repository_id)
    except ValueError:
        raise HTTPException(status_code=400, detail=f"repositoryId is not a valid UUID: {body.repository_id!r}")

    try:
        async with store.session() as session:
            files = await store.get_files_with_content(session, repository_id)
    except Exception as exc:
        logger.exception("Database error during /architecture")
        raise HTTPException(status_code=502, detail=f"Database error: {exc}")

    if not files:
        raise HTTPException(
            status_code=404,
            detail="No indexed chunks found for this repository. Call /index first.",
        )

    graph = build_architecture_graph(files)

    return ArchitectureResponse(
        nodes=[ArchitectureNode(id=n.id, language=n.language) for n in graph.nodes],
        edges=[ArchitectureEdge(source=e.source, target=e.target) for e in graph.edges],
    )
