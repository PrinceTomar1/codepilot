"""
End-to-end wire test for POST /query/stream: drives the real FastAPI app through an in-process
ASGI transport (no network, no DB, no real LLM) and asserts the Server-Sent Events contract the
Spring backend relies on -- a text/event-stream response, incremental `token` frames, and a
terminal `done` frame carrying the full answer + citations. Also covers the pre-stream 503 when
no LLM is configured.
"""
from __future__ import annotations

import json

import httpx
import pytest

from app.deps import get_embedding_provider, get_llm_client, get_vector_store
from app.routers import query as query_router
from app.services.vector_store import RetrievedChunk
from main import app

CHUNK = RetrievedChunk(
    file_path="src/foo.py", language="python", start_line=1, end_line=5,
    content="def foo(): return 42", distance=0.1,
)


class _FakeLLM:
    provider = "anthropic"

    def __init__(self, configured=True, deltas=("foo ", "returns ", "42")):
        self.configured = configured
        self._deltas = deltas

    async def stream(self, *args, **kwargs):
        for d in self._deltas:
            yield d


def _parse_sse(text: str) -> list[tuple[str, dict]]:
    frames = []
    for block in text.strip().split("\n\n"):
        event = data = None
        for line in block.splitlines():
            if line.startswith("event: "):
                event = line[len("event: ") :]
            elif line.startswith("data: "):
                data = json.loads(line[len("data: ") :])
        if event:
            frames.append((event, data))
    return frames


@pytest.fixture
def client(monkeypatch):
    async def _fake_retrieve(body, store, embedder):
        return [CHUNK], True

    monkeypatch.setattr(query_router, "_retrieve_context", _fake_retrieve)
    app.dependency_overrides[get_vector_store] = lambda: object()
    app.dependency_overrides[get_embedding_provider] = lambda: object()
    transport = httpx.ASGITransport(app=app)
    try:
        yield httpx.AsyncClient(transport=transport, base_url="http://test")
    finally:
        app.dependency_overrides.pop(get_vector_store, None)
        app.dependency_overrides.pop(get_embedding_provider, None)


async def test_stream_emits_token_frames_then_a_done_frame(client):
    app.dependency_overrides[get_llm_client] = lambda: _FakeLLM()
    try:
        async with client:
            resp = await client.post(
                "/query/stream",
                json={"repositoryId": "11111111-1111-1111-1111-111111111111", "question": "what does foo do?"},
            )
    finally:
        app.dependency_overrides.pop(get_llm_client, None)

    assert resp.status_code == 200
    assert resp.headers["content-type"].startswith("text/event-stream")

    frames = _parse_sse(resp.text)
    kinds = [k for k, _ in frames]
    assert "token" in kinds
    assert kinds[-1] == "done"

    streamed = "".join(d["text"] for k, d in frames if k == "token")
    done = frames[-1][1]
    assert done["answer"] == "foo returns 42"
    assert streamed == "foo returns 42"
    assert done["chunksRetrieved"] == 1
    assert done["citations"][0]["filePath"] == "src/foo.py"


async def test_stream_returns_503_before_streaming_when_llm_unconfigured(client):
    app.dependency_overrides[get_llm_client] = lambda: _FakeLLM(configured=False)
    try:
        async with client:
            resp = await client.post(
                "/query/stream",
                json={"repositoryId": "11111111-1111-1111-1111-111111111111", "question": "hi"},
            )
    finally:
        app.dependency_overrides.pop(get_llm_client, None)

    assert resp.status_code == 503
    assert "not configured" in resp.json()["error"].lower()
