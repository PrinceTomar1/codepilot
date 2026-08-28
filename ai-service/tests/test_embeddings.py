import math

import pytest

from app.services.embeddings import LocalHashEmbeddingProvider, tokenize


def test_tokenize_splits_camel_case_and_snake_case():
    tokens = tokenize("getUserById fetch_user_by_id HTTPServer")
    assert "get" in tokens
    assert "user" in tokens
    assert "by" in tokens
    assert "id" in tokens
    assert "fetch" in tokens
    assert "server" in tokens


@pytest.mark.asyncio
async def test_local_embedding_is_deterministic():
    provider = LocalHashEmbeddingProvider(dim=1536)
    text = "public class UserService { void getUserById(int id) {} }"
    vec1 = await provider.embed_one(text)
    vec2 = await provider.embed_one(text)
    assert vec1 == vec2


@pytest.mark.asyncio
async def test_local_embedding_has_correct_dimension():
    provider = LocalHashEmbeddingProvider(dim=1536)
    vec = await provider.embed_one("hello world")
    assert len(vec) == 1536


@pytest.mark.asyncio
async def test_local_embedding_is_l2_normalized():
    provider = LocalHashEmbeddingProvider(dim=1536)
    vec = await provider.embed_one("some reasonably long piece of source code with many tokens")
    norm = math.sqrt(sum(v * v for v in vec))
    assert norm == pytest.approx(1.0, abs=1e-6)


@pytest.mark.asyncio
async def test_local_embedding_empty_text_is_zero_vector():
    provider = LocalHashEmbeddingProvider(dim=1536)
    vec = await provider.embed_one("   ")
    assert all(v == 0.0 for v in vec)


@pytest.mark.asyncio
async def test_local_embedding_similar_texts_are_closer_than_unrelated():
    provider = LocalHashEmbeddingProvider(dim=1536)

    def cosine(a, b):
        dot = sum(x * y for x, y in zip(a, b))
        na = math.sqrt(sum(x * x for x in a))
        nb = math.sqrt(sum(y * y for y in b))
        return dot / (na * nb) if na and nb else 0.0

    v_a = await provider.embed_one("def get_user_by_id(user_id): return db.query(user_id)")
    v_b = await provider.embed_one("def get_user_by_id(uid): return database.query(uid)")
    v_c = await provider.embed_one("The quick brown fox jumps over the lazy dog in the park")

    sim_related = cosine(v_a, v_b)
    sim_unrelated = cosine(v_a, v_c)
    assert sim_related > sim_unrelated
