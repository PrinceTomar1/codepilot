from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException

from app.agents.review_orchestrator import ReviewOrchestrator
from app.deps import get_llm_client
from app.models.schemas import ReviewRequest, ReviewResponse
from app.services.llm import LLMClient, LLMNotConfiguredError, LLMRateLimitedError

logger = logging.getLogger("codepilot.review")

router = APIRouter()
_orchestrator = ReviewOrchestrator()


@router.post("/review", response_model=ReviewResponse)
async def review_pull_request(
    body: ReviewRequest,
    llm: LLMClient = Depends(get_llm_client),
) -> ReviewResponse:
    if not body.files:
        raise HTTPException(status_code=400, detail="files must not be empty")

    try:
        return await _orchestrator.run(llm, body.files)
    except LLMNotConfiguredError as exc:
        raise HTTPException(status_code=503, detail=str(exc))
    except LLMRateLimitedError as exc:
        raise HTTPException(status_code=429, detail=str(exc))
    except Exception as exc:
        logger.exception("Unhandled error during /review")
        raise HTTPException(status_code=502, detail=f"Review generation error: {exc}")
