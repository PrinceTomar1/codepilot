"""
A real bug found via live testing (not caught by any prior test -- there was zero test coverage
for the PR review path): every review agent's LLM call was wrapped in a bare `except Exception:
return []`, and ReviewOrchestrator._summarize() had the same blanket catch. That's the right
behavior for a genuinely agent-specific failure (malformed JSON, a parsing hiccup) -- one bad
agent shouldn't sink the whole review. But it also silently swallowed LLMNotConfiguredError and
LLMRateLimitedError, which are SYSTEMIC failures: if the LLM can't be reached at all, every agent
fails the same way, findings come back empty, and the orchestrator's summary fallback then
declares "No significant issues were found" -- a PASSING-LOOKING review that never actually ran.
These tests lock in the fix: those two exception types must propagate up to the /review router,
which already correctly turns them into a 503/429 (see review.py) -- they must never be swallowed
into a fake empty result.
"""
from __future__ import annotations

from unittest.mock import AsyncMock

import pytest

from app.agents.bug_agent import BugDetectionAgent
from app.agents.quality_agent import CodeQualityAgent
from app.agents.review_orchestrator import ReviewOrchestrator
from app.models.schemas import ReviewFileInput
from app.services.llm import LLMNotConfiguredError, LLMRateLimitedError

FILES = [ReviewFileInput(path="src/foo.py", diff="+bar()", full_content="def bar(): pass")]


async def test_base_agent_propagates_not_configured_error():
    llm = AsyncMock()
    llm.complete.side_effect = LLMNotConfiguredError("LLM not configured: set GEMINI_API_KEY")

    with pytest.raises(LLMNotConfiguredError):
        await BugDetectionAgent().review(llm, FILES)


async def test_base_agent_propagates_rate_limited_error():
    llm = AsyncMock()
    llm.complete.side_effect = LLMRateLimitedError("quota exceeded")

    with pytest.raises(LLMRateLimitedError):
        await BugDetectionAgent().review(llm, FILES)


async def test_base_agent_still_swallows_generic_failures():
    # Agent-specific failures (a flaky call, malformed output upstream) should NOT take down the
    # whole review -- only the two systemic exception types above should propagate.
    llm = AsyncMock()
    llm.complete.side_effect = ValueError("unexpected upstream error")

    findings = await BugDetectionAgent().review(llm, FILES)

    assert findings == []


async def test_quality_agent_propagates_not_configured_error():
    llm = AsyncMock()
    llm.complete.side_effect = LLMNotConfiguredError("LLM not configured: set GEMINI_API_KEY")

    with pytest.raises(LLMNotConfiguredError):
        await CodeQualityAgent().review_categorized(llm, FILES)


async def test_quality_agent_still_swallows_generic_failures():
    llm = AsyncMock()
    llm.complete.side_effect = ValueError("unexpected upstream error")

    smells, perf = await CodeQualityAgent().review_categorized(llm, FILES)

    assert smells == [] and perf == []


async def test_orchestrator_propagates_not_configured_error_instead_of_returning_empty_review():
    llm = AsyncMock()
    llm.complete.side_effect = LLMNotConfiguredError("LLM not configured: set GEMINI_API_KEY")

    with pytest.raises(LLMNotConfiguredError):
        await ReviewOrchestrator().run(llm, FILES)


async def test_orchestrator_propagates_rate_limited_error_instead_of_returning_empty_review():
    llm = AsyncMock()
    llm.complete.side_effect = LLMRateLimitedError("quota exceeded")

    with pytest.raises(LLMRateLimitedError):
        await ReviewOrchestrator().run(llm, FILES)
