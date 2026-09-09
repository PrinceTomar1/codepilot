"""
answer_question_stream() is the streaming counterpart to answer_question(). It yields
("token", str) items as the answer is generated and exactly one ("final", (answer, citations))
with the canonical result. The contract these tests lock in:

  - an ordinary grounded answer streams straight through; the "final" answer equals the
    concatenated tokens and carries citations derived from the retrieved chunks;
  - chitchat / empty-index are answered without any LLM call, same as answer_question();
  - when the model opens with a strict refusal or the general-knowledge marker, the stream hands
    off to answer_question() (which owns the refusal-retry / general-knowledge / deterministic
    fallback logic) and emits ITS result -- so "final" stays authoritative;
  - a provider rate-limit hit before anything is streamed degrades the same way answer_question()
    does (chunk listing when a keyword matched, refusal otherwise).
"""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import pytest

from app.services import rag
from app.services.llm import LLMRateLimitedError
from app.services.rag import (
    GENERAL_KNOWLEDGE_MARKER,
    NO_CONTEXT_ANSWER,
    answer_question_stream,
)
from app.services.vector_store import RetrievedChunk

CHUNK = RetrievedChunk(
    file_path="src/foo.py", language="python", start_line=1, end_line=5,
    content="def foo(): return 42", distance=0.1,
)


def _llm_with_stream(deltas):
    llm = MagicMock()

    async def _stream(*args, **kwargs):
        for d in deltas:
            yield d

    llm.stream = _stream
    return llm


async def _run(agen):
    tokens: list[str] = []
    final = None
    async for kind, payload in agen:
        if kind == "token":
            tokens.append(payload)
        else:
            final = payload
    return tokens, final


async def test_ordinary_answer_streams_through_with_citations():
    llm = _llm_with_stream(["foo() ", "returns ", "42 (src/foo.py:1-5)."])

    tokens, final = await _run(answer_question_stream(llm, "what does foo do?", [CHUNK]))

    assert "".join(tokens) == "foo() returns 42 (src/foo.py:1-5)."
    answer, citations = final
    assert answer == "foo() returns 42 (src/foo.py:1-5)."
    assert len(citations) == 1
    assert citations[0].file_path == "src/foo.py"


async def test_long_answer_flushes_and_keeps_streaming():
    deltas = [f"part{i} " for i in range(60)]  # well past the sniff threshold
    llm = _llm_with_stream(deltas)

    tokens, final = await _run(answer_question_stream(llm, "explain foo", [CHUNK]))

    assert len(tokens) > 1  # streamed incrementally, not one blob
    assert "".join(tokens) == "".join(deltas)
    assert final[0] == "".join(deltas).strip()


async def test_chitchat_answered_without_touching_the_llm():
    llm = MagicMock()
    llm.stream = MagicMock(side_effect=AssertionError("llm.stream must not be called for chitchat"))

    tokens, final = await _run(answer_question_stream(llm, "thanks!", [CHUNK]))

    assert "".join(tokens)  # a friendly reply was streamed
    assert final[1] == []


async def test_empty_index_returns_no_context_answer():
    llm = MagicMock()
    llm.stream = MagicMock(side_effect=AssertionError("llm.stream must not be called with no chunks"))

    tokens, final = await _run(answer_question_stream(llm, "where is auth?", []))

    assert final == (NO_CONTEXT_ANSWER, [])


async def test_refusal_opening_hands_off_to_answer_question():
    # The stream opens with the strict refusal; answer_question() then rescues it (off-topic
    # check says INSCOPE -> corrective retry produces a real grounded answer).
    llm = _llm_with_stream([NO_CONTEXT_ANSWER])
    llm.complete = AsyncMock(
        side_effect=[NO_CONTEXT_ANSWER, "INSCOPE", "Actually foo returns 42 (src/foo.py:1-5)."]
    )

    tokens, final = await _run(answer_question_stream(llm, "explain foo line by line", [CHUNK]))

    answer, citations = final
    assert answer == "Actually foo returns 42 (src/foo.py:1-5)."
    assert "".join(tokens) == answer
    assert len(citations) == 1


async def test_general_knowledge_marker_opening_hands_off_and_drops_citations():
    llm = _llm_with_stream([GENERAL_KNOWLEDGE_MARKER + "\n\nParis is the capital of France."])
    llm.complete = AsyncMock(
        return_value=GENERAL_KNOWLEDGE_MARKER + "\n\nParis is the capital of France."
    )

    tokens, final = await _run(answer_question_stream(llm, "capital of France?", [CHUNK]))

    answer, citations = final
    assert "Paris is the capital of France." in answer
    assert citations == []
    assert "".join(tokens) == answer


async def test_rate_limit_before_any_token_falls_back_to_chunk_listing():
    llm = MagicMock()

    async def _stream(*args, **kwargs):
        raise LLMRateLimitedError("quota exhausted")
        yield  # pragma: no cover

    llm.stream = _stream

    tokens, final = await _run(
        answer_question_stream(llm, "explain 4sum", [CHUNK], has_keyword_match=True)
    )

    answer, citations = final
    assert "src/foo.py" in answer
    assert len(citations) == 1
    assert "".join(tokens) == answer


async def test_rate_limit_without_keyword_match_returns_refusal():
    llm = MagicMock()

    async def _stream(*args, **kwargs):
        raise LLMRateLimitedError("quota exhausted")
        yield  # pragma: no cover

    llm.stream = _stream

    tokens, final = await _run(
        answer_question_stream(llm, "who rules the world", [CHUNK], has_keyword_match=False)
    )

    assert final == (NO_CONTEXT_ANSWER, [])


async def test_rate_limit_after_streaming_started_propagates():
    async def _stream(*args, **kwargs):
        yield "here is a real partial answer that is definitely longer than the sniff threshold " * 3
        raise LLMRateLimitedError("quota exhausted mid-stream")

    llm = MagicMock()
    llm.stream = _stream

    with pytest.raises(LLMRateLimitedError):
        await _run(answer_question_stream(llm, "explain foo", [CHUNK]))
