"""
Conversation memory: a follow-up question like "does it handle errors?" needs prior turns to
resolve what "it" refers to. These tests assert the history actually reaches the prompt sent to
the LLM, that it's capped and ordered correctly, and that answers still stay grounded in the
CONTEXT chunks (not fabricated from the model's own prior answer) when no history is given.
"""
from __future__ import annotations

from unittest.mock import AsyncMock

from app.models.schemas import QaTurn
from app.services.rag import MAX_HISTORY_TURNS, build_query_prompt, answer_question
from app.services.vector_store import RetrievedChunk

CHUNK = RetrievedChunk(
    file_path="src/foo.py", language="python", start_line=1, end_line=5,
    content="def foo(): return 42", distance=0.1,
)


def test_build_query_prompt_omits_history_block_when_empty():
    prompt = build_query_prompt("What does foo do?", [CHUNK], history=[])
    assert "PRIOR CONVERSATION" not in prompt


def test_build_query_prompt_includes_history_in_order():
    history = [
        QaTurn(question="What is foo.py?", answer="It's a helper module."),
        QaTurn(question="What does foo() do?", answer="It returns 42."),
    ]
    prompt = build_query_prompt("Does it handle errors?", [CHUNK], history=history)

    assert "PRIOR CONVERSATION" in prompt
    first_idx = prompt.index("What is foo.py?")
    second_idx = prompt.index("What does foo() do?")
    context_idx = prompt.index("CONTEXT:")
    question_idx = prompt.index("QUESTION:")

    assert first_idx < second_idx < context_idx < question_idx


def test_build_query_prompt_caps_history_to_most_recent_turns():
    history = [QaTurn(question=f"q{i}", answer=f"a{i}") for i in range(MAX_HISTORY_TURNS + 3)]
    prompt = build_query_prompt("follow-up", [CHUNK], history=history)

    for i in range(3):
        assert f"q{i}" not in prompt
    for i in range(3, MAX_HISTORY_TURNS + 3):
        assert f"q{i}" in prompt


async def test_answer_question_passes_history_into_prompt():
    llm = AsyncMock()
    llm.complete.return_value = "It does not handle errors (src/foo.py:1-5)."
    history = [QaTurn(question="What is foo.py?", answer="It's a helper module.")]

    await answer_question(llm, "Does it handle errors?", [CHUNK], history)

    called_prompt = llm.complete.call_args.kwargs["user"]
    assert "What is foo.py?" in called_prompt
    assert "PRIOR CONVERSATION" in called_prompt


async def test_answer_question_works_without_history():
    llm = AsyncMock()
    llm.complete.return_value = "foo() returns 42 (src/foo.py:1-5)."

    answer, citations = await answer_question(llm, "What does foo do?", [CHUNK])

    assert "42" in answer
    assert len(citations) == 1
    called_prompt = llm.complete.call_args.kwargs["user"]
    assert "PRIOR CONVERSATION" not in called_prompt
