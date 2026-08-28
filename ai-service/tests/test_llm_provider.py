"""
LLMClient supports three providers (anthropic, gemini, ollama) selected via AI_PROVIDER. These
tests cover the dispatch/configuration logic -- which client gets constructed, and which env var
name shows up in the "not configured" error -- without making a real network call to any of them
(ollama's real-request behavior, including a genuinely unreachable server, is covered separately
in test_llm_ollama.py via a mocked httpx transport).
"""
from __future__ import annotations

import pytest

from app.config import Settings
from app.services.llm import LLMClient, LLMNotConfiguredError


def _settings(**overrides) -> Settings:
    defaults = dict(
        AI_PROVIDER="anthropic",
        ANTHROPIC_API_KEY=None,
        ANTHROPIC_MODEL="claude-sonnet-4-5-20250929",
        GEMINI_API_KEY=None,
        GEMINI_MODEL="gemini-3.6-flash",
        OLLAMA_BASE_URL="http://localhost:11434",
        OLLAMA_MODEL="qwen2.5:7b-instruct",
    )
    defaults.update(overrides)
    return Settings(**defaults)


def test_defaults_to_anthropic_provider():
    client = LLMClient(_settings())
    assert client.provider == "anthropic"


def test_anthropic_unconfigured_without_key():
    client = LLMClient(_settings(AI_PROVIDER="anthropic", ANTHROPIC_API_KEY=None))
    assert client.configured is False


def test_anthropic_configured_with_key():
    client = LLMClient(_settings(AI_PROVIDER="anthropic", ANTHROPIC_API_KEY="sk-ant-fake"))
    assert client.configured is True


def test_gemini_unconfigured_without_key():
    client = LLMClient(_settings(AI_PROVIDER="gemini", GEMINI_API_KEY=None))
    assert client.configured is False


def test_gemini_configured_with_key():
    client = LLMClient(_settings(AI_PROVIDER="gemini", GEMINI_API_KEY="fake-gemini-key"))
    assert client.configured is True


def test_gemini_key_alone_does_not_configure_anthropic_provider():
    # Setting AI_PROVIDER=anthropic but only providing a Gemini key should NOT accidentally
    # configure anything -- the selected provider's own key is what matters.
    client = LLMClient(_settings(AI_PROVIDER="anthropic", GEMINI_API_KEY="fake-gemini-key"))
    assert client.configured is False


async def test_anthropic_not_configured_error_names_anthropic_key():
    client = LLMClient(_settings(AI_PROVIDER="anthropic", ANTHROPIC_API_KEY=None))
    with pytest.raises(LLMNotConfiguredError, match="ANTHROPIC_API_KEY"):
        await client.complete(system="s", user="u")


async def test_gemini_not_configured_error_names_gemini_key():
    client = LLMClient(_settings(AI_PROVIDER="gemini", GEMINI_API_KEY=None))
    with pytest.raises(LLMNotConfiguredError, match="GEMINI_API_KEY"):
        await client.complete(system="s", user="u")


def test_provider_selection_is_case_insensitive():
    client = LLMClient(_settings(AI_PROVIDER="GEMINI", GEMINI_API_KEY="fake-gemini-key"))
    assert client.provider == "gemini"
    assert client.configured is True


def test_ollama_is_configured_with_no_key_at_all():
    # Unlike anthropic/gemini, ollama needs no API key -- selecting it is enough. Whether the
    # local server is actually reachable can only be known at request time.
    client = LLMClient(_settings(AI_PROVIDER="ollama"))
    assert client.provider == "ollama"
    assert client.configured is True


def test_selecting_ollama_does_not_configure_anthropic_or_gemini():
    client = LLMClient(_settings(AI_PROVIDER="ollama", ANTHROPIC_API_KEY="sk-ant-fake", GEMINI_API_KEY="fake"))
    assert client._anthropic_client is None
    assert client._gemini_client is None
