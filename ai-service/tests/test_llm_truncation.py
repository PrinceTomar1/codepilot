"""
A real bug: a Q&A answer that hit the model's max-token limit was silently cut off mid-sentence,
with nothing telling the user why. These tests lock in the fix -- LLMClient.complete() must detect
a truncated response (via the provider's own stop/finish reason, not by guessing from the text)
and append a visible note, for both providers.
"""
from __future__ import annotations

from types import SimpleNamespace
from unittest.mock import AsyncMock

from app.config import Settings
from app.services.llm import TRUNCATION_NOTE, LLMClient


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


async def test_anthropic_truncated_response_gets_note_appended():
    client = LLMClient(_settings(AI_PROVIDER="anthropic"))
    client._anthropic_client = AsyncMock()
    client._anthropic_client.messages.create.return_value = SimpleNamespace(
        content=[SimpleNamespace(type="text", text="This answer got cut off half")],
        stop_reason="max_tokens",
    )

    result = await client.complete(system="s", user="u")

    assert result.startswith("This answer got cut off half")
    assert TRUNCATION_NOTE in result


async def test_anthropic_complete_response_has_no_note():
    client = LLMClient(_settings(AI_PROVIDER="anthropic"))
    client._anthropic_client = AsyncMock()
    client._anthropic_client.messages.create.return_value = SimpleNamespace(
        content=[SimpleNamespace(type="text", text="A complete answer.")],
        stop_reason="end_turn",
    )

    result = await client.complete(system="s", user="u")

    assert result == "A complete answer."
    assert TRUNCATION_NOTE not in result


async def test_gemini_truncated_response_gets_note_appended():
    client = LLMClient(_settings(AI_PROVIDER="gemini"))
    client._gemini_client = AsyncMock()
    client._gemini_client.aio.models.generate_content.return_value = SimpleNamespace(
        text="This answer also got cut off",
        candidates=[SimpleNamespace(finish_reason="MAX_TOKENS")],
    )

    result = await client.complete(system="s", user="u")

    assert result.startswith("This answer also got cut off")
    assert TRUNCATION_NOTE in result


async def test_gemini_complete_response_has_no_note():
    client = LLMClient(_settings(AI_PROVIDER="gemini"))
    client._gemini_client = AsyncMock()
    client._gemini_client.aio.models.generate_content.return_value = SimpleNamespace(
        text="A complete Gemini answer.",
        candidates=[SimpleNamespace(finish_reason="STOP")],
    )

    result = await client.complete(system="s", user="u")

    assert result == "A complete Gemini answer."
    assert TRUNCATION_NOTE not in result


async def test_gemini_no_candidates_does_not_crash():
    client = LLMClient(_settings(AI_PROVIDER="gemini"))
    client._gemini_client = AsyncMock()
    client._gemini_client.aio.models.generate_content.return_value = SimpleNamespace(
        text="", candidates=[]
    )

    result = await client.complete(system="s", user="u")

    assert result == ""
