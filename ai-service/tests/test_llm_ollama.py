"""
The ollama provider talks to a locally-running Ollama server over plain HTTP (no API key, no
external network call at all) -- these tests cover its actual request/response handling by
mocking httpx.AsyncClient.post, the same way test_llm_thinking_config.py mocks the Gemini SDK
directly rather than making a real network call.
"""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import httpx
import pytest

from app.config import Settings
from app.services.llm import LLMClient, LLMNotConfiguredError


def _settings(**overrides) -> Settings:
    defaults = dict(
        AI_PROVIDER="ollama",
        OLLAMA_BASE_URL="http://localhost:11434",
        OLLAMA_MODEL="qwen2.5:7b-instruct",
    )
    defaults.update(overrides)
    return Settings(**defaults)


def _mock_response(content: str = "answer", done_reason: str = "stop") -> MagicMock:
    response = MagicMock()
    response.raise_for_status = MagicMock()
    response.json = MagicMock(return_value={
        "message": {"role": "assistant", "content": content},
        "done_reason": done_reason,
    })
    return response


async def test_complete_returns_the_message_content():
    client = LLMClient(_settings())
    with patch.object(httpx.AsyncClient, "post", AsyncMock(return_value=_mock_response("Paris is the capital."))):
        answer = await client.complete(system="s", user="what is the capital of France?")
    assert answer == "Paris is the capital."


async def test_sends_the_model_system_and_user_message_and_options_ollama_expects():
    client = LLMClient(_settings(OLLAMA_MODEL="qwen2.5:7b-instruct"))
    mock_post = AsyncMock(return_value=_mock_response("ok"))
    with patch.object(httpx.AsyncClient, "post", mock_post):
        await client.complete(system="be helpful", user="hi", max_tokens=512, temperature=0.3)

    _, kwargs = mock_post.call_args
    body = kwargs["json"]
    assert body["model"] == "qwen2.5:7b-instruct"
    assert body["messages"] == [
        {"role": "system", "content": "be helpful"},
        {"role": "user", "content": "hi"},
    ]
    assert body["stream"] is False
    assert body["options"]["temperature"] == 0.3
    assert body["options"]["num_predict"] == 512


async def test_appends_truncation_note_when_ollama_reports_length_cutoff():
    client = LLMClient(_settings())
    with patch.object(httpx.AsyncClient, "post", AsyncMock(return_value=_mock_response("cut off mid", done_reason="length"))):
        answer = await client.complete(system="s", user="u")
    assert "cut off mid" in answer
    assert "cut off" in answer.lower() and "length limit" in answer.lower()


async def test_unreachable_ollama_server_raises_llm_not_configured_with_a_clear_hint():
    # A connection refused (nothing listening on OLLAMA_BASE_URL, e.g. `ollama serve` never
    # started) is the single most likely real-world failure for a self-hosted provider -- it
    # should read as clearly actionable, not as an opaque stack trace.
    client = LLMClient(_settings())
    with patch.object(httpx.AsyncClient, "post", AsyncMock(side_effect=httpx.ConnectError("refused"))):
        with pytest.raises(LLMNotConfiguredError, match="Ollama not reachable"):
            await client.complete(system="s", user="u")
