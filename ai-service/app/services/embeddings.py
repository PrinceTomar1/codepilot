"""
Pluggable embedding providers. Both implementations produce vectors of a
FIXED dimensionality (settings.EMBEDDING_DIM, 1536) so the pgvector column
never has to change regardless of which provider is active.
"""
from __future__ import annotations

import hashlib
import re
from abc import ABC, abstractmethod

import httpx
import numpy as np

from app.config import Settings

# Splits on non-identifier characters, then further splits camelCase and
# snake_case identifiers so code tokens like `getUserById` embed as
# meaningful sub-tokens (get, user, by, id) rather than one opaque blob.
_WORD_RE = re.compile(r"[A-Za-z0-9_]+")
_CAMEL_SPLIT_RE = re.compile(
    r"(?<=[a-z0-9])(?=[A-Z])"  # ...id|Foo -> id, Foo
    r"|(?<=[A-Za-z])(?=[0-9])"  # ...foo|2 -> foo, 2
    r"|(?<=[A-Z])(?=[A-Z][a-z])"  # HTTP|Server -> HTTP, Server (acronym boundary)
)


def tokenize(text: str) -> list[str]:
    tokens: list[str] = []
    for raw in _WORD_RE.findall(text):
        for part in raw.split("_"):
            if not part:
                continue
            for sub in _CAMEL_SPLIT_RE.split(part):
                sub = sub.strip().lower()
                if sub:
                    tokens.append(sub)
    return tokens


class EmbeddingProvider(ABC):
    @abstractmethod
    async def embed(self, texts: list[str]) -> list[list[float]]:
        """Embed a batch of texts, returning one fixed-length vector per text."""
        raise NotImplementedError

    async def embed_one(self, text: str) -> list[float]:
        return (await self.embed([text]))[0]


class LocalHashEmbeddingProvider(EmbeddingProvider):
    """
    Deterministic, dependency-light fallback embedding provider.

    This is a hashing-trick bag-of-words embedding: every token is hashed
    into one of `dim` buckets (with a sign derived from a second hash to
    reduce systematic collision bias, as in Weinberger et al.'s "feature
    hashing" / the scikit-learn HashingVectorizer approach), accumulated
    into a vector, and the result is L2-normalized.

    It is a legitimate, explainable, zero-external-dependency baseline
    that captures rough token/identifier overlap between query and code
    -- it trades semantic quality for having no API keys, network calls,
    or heavyweight ML frameworks (torch, sentence-transformers, ...).
    Swap EMBEDDING_PROVIDER=openai for a real semantic-embedding model in
    production; the pgvector schema (1536-dim) does not need to change
    either way.
    """

    def __init__(self, dim: int = 1536):
        self.dim = dim

    def _embed_text(self, text: str) -> list[float]:
        vec = np.zeros(self.dim, dtype=np.float64)
        tokens = tokenize(text)
        if not tokens:
            return vec.tolist()

        for token in tokens:
            h = hashlib.sha1(token.encode("utf-8")).hexdigest()
            bucket = int(h[:8], 16) % self.dim
            sign = 1.0 if int(h[8:9], 16) % 2 == 0 else -1.0
            vec[bucket] += sign

        norm = np.linalg.norm(vec)
        if norm > 0:
            vec = vec / norm
        return vec.tolist()

    async def embed(self, texts: list[str]) -> list[list[float]]:
        # Pure CPU work; no I/O, so no need for real async concurrency here.
        return [self._embed_text(t) for t in texts]


class OpenAIEmbeddingProvider(EmbeddingProvider):
    """Calls OpenAI's embeddings API (text-embedding-3-small, natively 1536-dim)."""

    API_URL = "https://api.openai.com/v1/embeddings"
    BATCH_SIZE = 96

    def __init__(self, api_key: str, model: str, dim: int = 1536):
        if not api_key:
            raise ValueError("OPENAI_API_KEY is required for EMBEDDING_PROVIDER=openai")
        self.api_key = api_key
        self.model = model
        self.dim = dim

    async def embed(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        results: list[list[float]] = []
        async with httpx.AsyncClient(timeout=60.0) as client:
            for i in range(0, len(texts), self.BATCH_SIZE):
                batch = texts[i:i + self.BATCH_SIZE]
                resp = await client.post(
                    self.API_URL,
                    headers={"Authorization": f"Bearer {self.api_key}"},
                    json={"model": self.model, "input": batch, "dimensions": self.dim},
                )
                resp.raise_for_status()
                data = resp.json()
                # OpenAI preserves input order in `data`, but sort by index to be safe.
                ordered = sorted(data["data"], key=lambda d: d["index"])
                results.extend([item["embedding"] for item in ordered])
        return results


def get_embedding_provider(settings: Settings) -> EmbeddingProvider:
    if settings.EMBEDDING_PROVIDER.lower() == "openai":
        return OpenAIEmbeddingProvider(
            api_key=settings.OPENAI_API_KEY or "",
            model=settings.OPENAI_EMBEDDING_MODEL,
            dim=settings.EMBEDDING_DIM,
        )
    return LocalHashEmbeddingProvider(dim=settings.EMBEDDING_DIM)
