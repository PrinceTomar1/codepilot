"""
A real incident: Gemini's free tier caps at 20 requests/day, and hitting that cap raised an
uncaught google.genai.errors.ClientError deep in the SDK call stack. FastAPI's default handler
turned that into an opaque 500 "Internal server error" -- combined with the frontend having no
request timeout, the chatbot just hung on "Thinking..." forever with no way to tell what was
wrong. These tests lock in the fix: LLMClient.complete() must catch a 429 from either provider
and raise LLMRateLimitedError, which routers turn into a real 429 with an actionable message.
"""
from __future__ import annotations

from unittest.mock import AsyncMock

import anthropic
import httpx
import pytest
from google.genai import errors as genai_errors

from app.config import Settings
from app.services.llm import LLMClient, LLMRateLimitedError


def _settings(**overrides) -> Settings:
    defaults = dict(
        AI_PROVIDER="anthropic",
        ANTHROPIC_API_KEY="sk-ant-fake",
        ANTHROPIC_MODEL="claude-sonnet-4-5-20250929",
        GEMINI_API_KEY="fake-gemini-key",
        GEMINI_MODEL="gemini-3.6-flash",
    )
    defaults.update(overrides)
    return Settings(**defaults)


async def test_gemini_quota_exhausted_raises_rate_limited_error():
    client = LLMClient(_settings(AI_PROVIDER="gemini"))
    client._gemini_client = AsyncMock()
    fake_response = httpx.Response(429, request=httpx.Request("POST", "https://example.com"))
    client._gemini_client.aio.models.generate_content.side_effect = genai_errors.ClientError(
        429,
        {"error": {"message": "Quota exceeded... Please retry in 33s.", "status": "RESOURCE_EXHAUSTED"}},
        fake_response,
    )

    with pytest.raises(LLMRateLimitedError, match="33s"):
        await client.complete(system="s", user="u")


async def test_gemini_non_rate_limit_client_error_propagates_unchanged():
    client = LLMClient(_settings(AI_PROVIDER="gemini"))
    client._gemini_client = AsyncMock()
    fake_response = httpx.Response(400, request=httpx.Request("POST", "https://example.com"))
    client._gemini_client.aio.models.generate_content.side_effect = genai_errors.ClientError(
        400, {"error": {"message": "Bad request", "status": "INVALID_ARGUMENT"}}, fake_response,
    )

    with pytest.raises(genai_errors.ClientError):
        await client.complete(system="s", user="u")


async def test_anthropic_rate_limit_raises_rate_limited_error():
    client = LLMClient(_settings(AI_PROVIDER="anthropic"))
    client._anthropic_client = AsyncMock()
    fake_response = httpx.Response(429, request=httpx.Request("POST", "https://example.com"))
    client._anthropic_client.messages.create.side_effect = anthropic.RateLimitError(
        "rate limited", response=fake_response, body=None,
    )

    with pytest.raises(LLMRateLimitedError):
        await client.complete(system="s", user="u")
