"""
LLMClient.stream() is the token-by-token counterpart to complete() -- it's what lets the chatbot
render an answer as it's generated instead of blocking on the whole thing. These tests cover each
provider's streaming path with the SDK/transport mocked (no real network call), mirroring
test_llm_provider.py / test_llm_ollama.py, plus the rate-limit mapping that complete() already has.
"""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import anthropic
import httpx
import pytest
from google.genai import errors as genai_errors

from app.config import Settings
from app.services.llm import LLMClient, LLMNotConfiguredError, LLMRateLimitedError


def _settings(**overrides) -> Settings:
    defaults = dict(
        AI_PROVIDER="anthropic",
        ANTHROPIC_API_KEY="sk-ant-fake",
        ANTHROPIC_MODEL="claude-sonnet-4-5-20250929",
        GEMINI_API_KEY="fake-gemini-key",
        GEMINI_MODEL="gemini-3.6-flash",
        OLLAMA_BASE_URL="http://localhost:11434",
        OLLAMA_MODEL="qwen2.5:7b-instruct",
    )
    defaults.update(overrides)
    return Settings(**defaults)


async def _collect(agen) -> list[str]:
    return [chunk async for chunk in agen]


# --- Anthropic -------------------------------------------------------------

class _FakeAnthropicStream:
    def __init__(self, deltas: list[str], stop_reason: str = "end_turn"):
        self._deltas = deltas
        self._stop_reason = stop_reason

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    @property
    def text_stream(self):
        async def _gen():
            for d in self._deltas:
                yield d

        return _gen()

    async def get_final_message(self):
        return MagicMock(stop_reason=self._stop_reason)


async def test_anthropic_stream_yields_each_delta_in_order():
    client = LLMClient(_settings(AI_PROVIDER="anthropic"))
    client._anthropic_client = MagicMock()
    client._anthropic_client.messages.stream = MagicMock(
        return_value=_FakeAnthropicStream(["Hel", "lo ", "world"])
    )

    chunks = await _collect(client.stream(system="s", user="u"))

    assert "".join(chunks) == "Hello world"


async def test_anthropic_stream_appends_truncation_note_on_max_tokens():
    client = LLMClient(_settings(AI_PROVIDER="anthropic"))
    client._anthropic_client = MagicMock()
    client._anthropic_client.messages.stream = MagicMock(
        return_value=_FakeAnthropicStream(["cut off mid"], stop_reason="max_tokens")
    )

    text = "".join(await _collect(client.stream(system="s", user="u")))

    assert "cut off mid" in text
    assert "length limit" in text.lower()


async def test_anthropic_stream_maps_rate_limit_error():
    client = LLMClient(_settings(AI_PROVIDER="anthropic"))
    client._anthropic_client = MagicMock()
    fake_response = httpx.Response(429, request=httpx.Request("POST", "https://example.com"))
    client._anthropic_client.messages.stream = MagicMock(
        side_effect=anthropic.RateLimitError("rate limited", response=fake_response, body=None)
    )

    with pytest.raises(LLMRateLimitedError):
        await _collect(client.stream(system="s", user="u"))


# --- Gemini ---------------------------------------------------------------

def _gemini_chunk(text: str, finish_reason=None) -> MagicMock:
    chunk = MagicMock()
    chunk.text = text
    if finish_reason is None:
        chunk.candidates = []
    else:
        candidate = MagicMock()
        candidate.finish_reason = finish_reason
        chunk.candidates = [candidate]
    return chunk


async def test_gemini_stream_yields_each_delta_in_order():
    client = LLMClient(_settings(AI_PROVIDER="gemini"))
    client._gemini_client = MagicMock()

    async def _fake_stream(*args, **kwargs):
        for c in [_gemini_chunk("por "), _gemini_chunk("qué "), _gemini_chunk("no")]:
            yield c

    client._gemini_client.aio.models.generate_content_stream = AsyncMock(
        return_value=_fake_stream()
    )

    chunks = await _collect(client.stream(system="s", user="u"))
    assert "".join(chunks) == "por qué no"


async def test_gemini_stream_appends_truncation_note_on_max_tokens_finish():
    client = LLMClient(_settings(AI_PROVIDER="gemini"))
    client._gemini_client = MagicMock()

    async def _fake_stream(*args, **kwargs):
        yield _gemini_chunk("partial")
        yield _gemini_chunk("", finish_reason="MAX_TOKENS")

    client._gemini_client.aio.models.generate_content_stream = AsyncMock(
        return_value=_fake_stream()
    )

    text = "".join(await _collect(client.stream(system="s", user="u")))
    assert "partial" in text
    assert "length limit" in text.lower()


async def test_gemini_stream_maps_429_to_rate_limited_error():
    client = LLMClient(_settings(AI_PROVIDER="gemini"))
    client._gemini_client = MagicMock()
    fake_response = httpx.Response(429, request=httpx.Request("POST", "https://example.com"))

    async def _boom(*args, **kwargs):
        raise genai_errors.ClientError(
            429,
            {"error": {"message": "Quota exceeded... retry in 12s", "status": "RESOURCE_EXHAUSTED"}},
            fake_response,
        )
        yield  # pragma: no cover -- makes this an async generator

    client._gemini_client.aio.models.generate_content_stream = AsyncMock(return_value=_boom())

    with pytest.raises(LLMRateLimitedError, match="12s"):
        await _collect(client.stream(system="s", user="u"))


# --- Ollama -------------------------------------------------------------

class _FakeOllamaStreamCtx:
    def __init__(self, lines: list[str]):
        self._lines = lines

    async def __aenter__(self):
        response = MagicMock()
        response.raise_for_status = MagicMock()

        async def _aiter_lines():
            for line in self._lines:
                yield line

        response.aiter_lines = _aiter_lines
        return response

    async def __aexit__(self, *exc):
        return False


async def test_ollama_stream_yields_message_content_pieces():
    client = LLMClient(_settings(AI_PROVIDER="ollama"))
    lines = [
        '{"message": {"content": "Pa"}, "done": false}',
        '{"message": {"content": "ris"}, "done": false}',
        "",
        '{"message": {"content": ""}, "done": true, "done_reason": "stop"}',
    ]
    with patch.object(httpx.AsyncClient, "stream", MagicMock(return_value=_FakeOllamaStreamCtx(lines))):
        chunks = await _collect(client.stream(system="s", user="u"))
    assert "".join(chunks) == "Paris"


async def test_ollama_stream_appends_truncation_note_on_length_stop():
    client = LLMClient(_settings(AI_PROVIDER="ollama"))
    lines = [
        '{"message": {"content": "cut"}, "done": false}',
        '{"message": {"content": ""}, "done": true, "done_reason": "length"}',
    ]
    with patch.object(httpx.AsyncClient, "stream", MagicMock(return_value=_FakeOllamaStreamCtx(lines))):
        text = "".join(await _collect(client.stream(system="s", user="u")))
    assert "cut" in text
    assert "length limit" in text.lower()


async def test_ollama_stream_unreachable_server_raises_not_configured():
    client = LLMClient(_settings(AI_PROVIDER="ollama"))
    with patch.object(
        httpx.AsyncClient, "stream", MagicMock(side_effect=httpx.ConnectError("refused"))
    ):
        with pytest.raises(LLMNotConfiguredError, match="Ollama not reachable"):
            await _collect(client.stream(system="s", user="u"))


# --- dispatch -----------------------------------------------------------

async def test_stream_raises_not_configured_when_no_provider():
    client = LLMClient(_settings(AI_PROVIDER="anthropic", ANTHROPIC_API_KEY=None))
    with pytest.raises(LLMNotConfiguredError, match="ANTHROPIC_API_KEY"):
        await _collect(client.stream(system="s", user="u"))
