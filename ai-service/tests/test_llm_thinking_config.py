"""
Chatbot latency was traced to Gemini's internal "thinking" step -- a trivial "say OK" prompt was
observed spending ~95 reasoning tokens before writing anything. LLMClient.complete(fast=True) cuts
that down for synthesis-style calls (answer this from the given context) that don't need
exploratory reasoning. Gemini 3.x models reject thinking_budget=0 (the old Gemini 2.5 way to
disable thinking) and require thinking_level="minimal" instead -- sending both in one request is
itself a 400 error -- so the two model families need different config shapes. These tests lock in
that dispatch without a live network call.
"""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

from google.genai import types as genai_types

from app.config import Settings
from app.services.llm import LLMClient


def _settings(**overrides) -> Settings:
    defaults = dict(
        AI_PROVIDER="gemini",
        GEMINI_API_KEY="fake-gemini-key",
        GEMINI_MODEL="gemini-3.6-flash",
    )
    defaults.update(overrides)
    return Settings(**defaults)


def _mock_response(text: str = "answer") -> MagicMock:
    response = MagicMock()
    response.text = text
    candidate = MagicMock()
    candidate.finish_reason = "STOP"
    response.candidates = [candidate]
    return response


async def test_fast_true_uses_thinking_level_minimal_on_gemini_3():
    client = LLMClient(_settings(GEMINI_MODEL="gemini-3.6-flash"))
    client._gemini_client = AsyncMock()
    client._gemini_client.aio.models.generate_content.return_value = _mock_response()

    await client.complete(system="s", user="u", fast=True)

    config = client._gemini_client.aio.models.generate_content.call_args.kwargs["config"]
    assert config.thinking_config.thinking_level == genai_types.ThinkingLevel.MINIMAL
    assert config.thinking_config.thinking_budget is None


async def test_fast_true_uses_thinking_budget_zero_on_older_gemini_models():
    client = LLMClient(_settings(GEMINI_MODEL="gemini-2.5-flash"))
    client._gemini_client = AsyncMock()
    client._gemini_client.aio.models.generate_content.return_value = _mock_response()

    await client.complete(system="s", user="u", fast=True)

    config = client._gemini_client.aio.models.generate_content.call_args.kwargs["config"]
    assert config.thinking_config.thinking_budget == 0
    assert config.thinking_config.thinking_level is None


async def test_fast_false_does_not_set_thinking_config():
    client = LLMClient(_settings())
    client._gemini_client = AsyncMock()
    client._gemini_client.aio.models.generate_content.return_value = _mock_response()

    await client.complete(system="s", user="u")  # fast defaults to False

    config = client._gemini_client.aio.models.generate_content.call_args.kwargs["config"]
    assert config.thinking_config is None
