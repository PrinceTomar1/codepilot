"""
Application configuration, loaded from environment variables / a .env file.

Uses pydantic-settings so config is validated once at startup and shared
via a cached singleton (`get_settings()`).
"""
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # Server
    PORT: int = 8000

    # Storage
    DATABASE_URL: str = "postgresql://codepilot:codepilot@localhost:5432/codepilot"

    # Embeddings
    EMBEDDING_PROVIDER: str = "local"  # "local" | "openai"
    EMBEDDING_DIM: int = 1536  # FIXED regardless of provider so the DB schema never changes
    OPENAI_API_KEY: str | None = None
    OPENAI_EMBEDDING_MODEL: str = "text-embedding-3-small"

    # LLM generation: "anthropic", "gemini", or "ollama" (a model running entirely on your own
    # machine -- no external API, no key, no per-request cost or quota). Only the selected
    # provider's settings matter -- LLMClient.configured reflects whichever one AI_PROVIDER points
    # at.
    AI_PROVIDER: str = "anthropic"
    ANTHROPIC_API_KEY: str | None = None
    ANTHROPIC_MODEL: str = "claude-sonnet-4-5-20250929"
    GEMINI_API_KEY: str | None = None
    GEMINI_MODEL: str = "gemini-3.6-flash"
    OLLAMA_BASE_URL: str = "http://localhost:11434"
    OLLAMA_MODEL: str = "qwen2.5:7b-instruct"


@lru_cache
def get_settings() -> Settings:
    return Settings()
