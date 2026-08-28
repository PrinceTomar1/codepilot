from __future__ import annotations

import json
import logging
import re
import uuid

from fastapi import APIRouter, Depends, HTTPException

from app.deps import get_llm_client, get_vector_store
from app.models.schemas import ImportantModule, OnboardingRequest, OnboardingResponse
from app.services.llm import UNTRUSTED_CONTENT_NOTICE, LLMClient, LLMNotConfiguredError, LLMRateLimitedError
from app.services.rag import ONBOARDING_SYSTEM_PROMPT, build_onboarding_prompt
from app.services.vector_store import VectorStore

logger = logging.getLogger("codepilot.onboarding")

router = APIRouter()

# Files that look like natural starting points get prioritized in the sample. Includes Next.js
# App Router conventions (page/layout/route) alongside the older main/app/index style -- a repo
# using page.tsx/layout.tsx as its real entry points previously matched none of these hints.
_ENTRY_POINT_HINTS = re.compile(
    r"(^|/)(main|app|index|application|program|startup|server|settings|config|page|layout|route)"
    r"\.(java|py|js|ts|jsx|tsx|go|rb)$"
    r"|(^|/)(pom\.xml|build\.gradle|package\.json|requirements\.txt|dockerfile|docker-compose\.ya?ml)$",
    re.IGNORECASE,
)

MAX_CHARS_BUDGET = 24000  # rough token-budget guard for the LLM prompt


def _extract_json_object(text: str) -> str | None:
    text = text.strip()
    fence_match = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.DOTALL)
    if fence_match:
        return fence_match.group(1)
    start = text.find("{")
    end = text.rfind("}")
    if start != -1 and end != -1 and end > start:
        return text[start:end + 1]
    return None


@router.post("/onboarding", response_model=OnboardingResponse)
async def onboarding(
    body: OnboardingRequest,
    store: VectorStore = Depends(get_vector_store),
    llm: LLMClient = Depends(get_llm_client),
) -> OnboardingResponse:
    try:
        repository_id = uuid.UUID(body.repository_id)
    except ValueError:
        raise HTTPException(status_code=400, detail=f"repositoryId is not a valid UUID: {body.repository_id!r}")

    try:
        async with store.session() as session:
            chunks = await store.sample_chunks_per_file(session, repository_id, per_file=2)
    except Exception as exc:
        logger.exception("Database error during /onboarding")
        raise HTTPException(status_code=502, detail=f"Database error: {exc}")

    if not chunks:
        raise HTTPException(
            status_code=404,
            detail="No indexed chunks found for this repository. Call /index first.",
        )

    # Prioritize entry-point-looking files, then fill up to a rough char budget.
    prioritized = sorted(chunks, key=lambda c: (0 if _ENTRY_POINT_HINTS.search(c.file_path) else 1, c.file_path))
    sample = []
    budget = MAX_CHARS_BUDGET
    for c in prioritized:
        if budget <= 0:
            break
        sample.append(c)
        budget -= len(c.content)

    prompt = build_onboarding_prompt(sample)

    try:
        raw = await llm.complete(
            system=ONBOARDING_SYSTEM_PROMPT + UNTRUSTED_CONTENT_NOTICE, user=prompt, max_tokens=3000
        )
    except LLMNotConfiguredError as exc:
        raise HTTPException(status_code=503, detail=str(exc))
    except LLMRateLimitedError as exc:
        raise HTTPException(status_code=429, detail=str(exc))
    except Exception as exc:
        logger.exception("LLM call failed during /onboarding")
        raise HTTPException(status_code=502, detail=f"LLM generation error: {exc}")

    candidate = _extract_json_object(raw)
    parsed: dict = {}
    if candidate:
        try:
            parsed = json.loads(candidate)
        except json.JSONDecodeError:
            logger.warning("onboarding: failed to parse LLM JSON, raw response: %r", raw[:1000])
            parsed = {}

    important_modules = [
        ImportantModule(path=str(m.get("path", "")), description=str(m.get("description", "")))
        for m in parsed.get("importantModules", [])
        if isinstance(m, dict) and m.get("path")
    ]

    read_first = [str(p) for p in parsed.get("readFirst", []) if isinstance(p, (str,))]
    if not read_first:
        read_first = [c.file_path for c in sample[:5]]

    if not important_modules:
        # Fall back to the sampled file list so the field is never empty.
        seen = set()
        for c in sample:
            if c.file_path in seen:
                continue
            seen.add(c.file_path)
            important_modules.append(ImportantModule(path=c.file_path, description=""))
            if len(important_modules) >= 10:
                break

    return OnboardingResponse(
        architectureOverview=str(parsed.get("architectureOverview", raw if not candidate else "")),
        importantModules=important_modules,
        setupInstructions=str(parsed.get("setupInstructions", "")),
        dataFlow=str(parsed.get("dataFlow", "")),
        readFirst=read_first,
    )
