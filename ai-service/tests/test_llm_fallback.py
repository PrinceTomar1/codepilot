"""
Real gap this closes: no individual free-tier LLM API has generous quota for sustained usage
(Gemini's free tier is 20 requests/day) -- when it runs out, every prior behavior was to surface
a 429 straight to the chatbot user. AI_FALLBACK_PROVIDER lets a rate limit degrade to a second
provider (typically AI_FALLBACK_PROVIDER=ollama, which has no quota at all) instead. These tests
lock in: the fallback fires only on LLMRateLimitedError, only when configured, never chains into a
fallback of its own, and -- for streaming -- only before anything has actually reached the caller
(restarting a fresh generation partway through an answer would duplicate or contradict what's
already been shown).
"""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import anthropic
import httpx
import pytest

from app.config import Settings
from app.services.llm import LLMClient, LLMRateLimitedError


def _settings(**overrides) -> Settings:
    defaults = dict(
        AI_PROVIDER="anthropic",
        AI_FALLBACK_PROVIDER=None,
        ANTHROPIC_API_KEY="sk-ant-fake",
        ANTHROPIC_MODEL="claude-sonnet-4-5-20250929",
        GEMINI_API_KEY="fake-gemini-key",
        GEMINI_MODEL="gemini-3.6-flash",
        OLLAMA_BASE_URL="http://localhost:11434",
        OLLAMA_MODEL="qwen2.5:7b-instruct",
    )
    defaults.update(overrides)
    return Settings(**defaults)


def _rate_limited_anthropic_client() -> MagicMock:
    client = MagicMock()
    fake_response = httpx.Response(429, request=httpx.Request("POST", "https://example.com"))
    client.messages.create = AsyncMock(
        side_effect=anthropic.RateLimitError("rate limited", response=fake_response, body=None)
    )
    return client


async def _collect(agen) -> list[str]:
    return [chunk async for chunk in agen]


# --- complete() ------------------------------------------------------------

async def test_falls_back_to_configured_provider_on_rate_limit():
    client = LLMClient(_settings(AI_PROVIDER="anthropic", AI_FALLBACK_PROVIDER="ollama"))
    client._anthropic_client = _rate_limited_anthropic_client()

    with patch.object(
        httpx.AsyncClient, "post",
        AsyncMock(return_value=MagicMock(
            raise_for_status=MagicMock(),
            json=MagicMock(return_value={"message": {"content": "answered by ollama"}}),
        )),
    ):
        answer = await client.complete(system="s", user="u")

    assert answer == "answered by ollama"


async def test_without_a_fallback_configured_the_rate_limit_still_propagates():
    client = LLMClient(_settings(AI_PROVIDER="anthropic", AI_FALLBACK_PROVIDER=None))
    client._anthropic_client = _rate_limited_anthropic_client()

    with pytest.raises(LLMRateLimitedError):
        await client.complete(system="s", user="u")


async def test_fallback_pointed_at_the_same_provider_as_primary_is_a_noop():
    client = LLMClient(_settings(AI_PROVIDER="anthropic", AI_FALLBACK_PROVIDER="anthropic"))
    assert client._fallback is None

    client._anthropic_client = _rate_limited_anthropic_client()
    with pytest.raises(LLMRateLimitedError):
        await client.complete(system="s", user="u")


def test_a_fallback_client_never_carries_its_own_fallback():
    # Otherwise a fallback that also rate-limits could keep chaining outward indefinitely.
    client = LLMClient(_settings(AI_PROVIDER="anthropic", AI_FALLBACK_PROVIDER="ollama"))
    assert client._fallback is not None
    assert client._fallback._fallback is None


# --- stream() ----------------------------------------------------------

async def test_stream_falls_back_when_the_primary_rate_limits_before_any_chunk():
    client = LLMClient(_settings(AI_PROVIDER="anthropic", AI_FALLBACK_PROVIDER="ollama"))

    async def _raising_stream(*args, **kwargs):
        raise LLMRateLimitedError("quota exhausted")
        yield  # pragma: no cover -- makes this an async generator

    client._stream_anthropic = _raising_stream
    client._fallback.stream = lambda *a, **k: _fake_ollama_stream(["ans", "wered by fallback"])

    chunks = await _collect(client.stream(system="s", user="u"))
    assert "".join(chunks) == "answered by fallback"


async def test_stream_does_not_fall_back_once_a_chunk_already_reached_the_caller():
    client = LLMClient(_settings(AI_PROVIDER="anthropic", AI_FALLBACK_PROVIDER="ollama"))

    async def _partial_then_raising_stream(*args, **kwargs):
        yield "partial answer, then the quota runs out"
        raise LLMRateLimitedError("quota exhausted mid-stream")

    client._stream_anthropic = _partial_then_raising_stream

    with pytest.raises(LLMRateLimitedError):
        await _collect(client.stream(system="s", user="u"))


async def _fake_ollama_stream(deltas: list[str]):
    for d in deltas:
        yield d
