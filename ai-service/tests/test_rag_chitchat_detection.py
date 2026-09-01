"""
The prompt-level chitchat carve-out (test_rag_chitchat.py) turned out not to be reliable across
LLM providers: a smaller local (Ollama) model correctly treated "hi"/"hello" as
chitchat per the prompt's instructions, but still returned the strict "not enough information"
refusal for "thanks", "okay thanks", and "ok cool" -- even though "thanks" is a literal example
word in that same prompt rule. Rather than continue tuning prompt wording indefinitely, chitchat
is now ALSO detected deterministically in code (answer_question() checks this before ever calling
the LLM), so the behavior is guaranteed correct regardless of which provider is active.
"""
from __future__ import annotations

from unittest.mock import AsyncMock

from app.services.rag import answer_question, chitchat_response, is_chitchat
from app.services.vector_store import RetrievedChunk

CHUNK = RetrievedChunk(
    file_path="weather_app.py", language="python", start_line=1, end_line=5,
    content="import requests", distance=0.1,
)


def test_recognizes_greetings_as_chitchat():
    for q in ["hi", "hey", "hello", "yo", "Hi!", "  hello  "]:
        assert is_chitchat(q), f"expected {q!r} to be chitchat"


def test_recognizes_acknowledgments_and_closers_as_chitchat():
    # The exact real-world failures: the local model refused every one of these.
    for q in ["thanks", "thank you", "okay thanks", "ok cool", "ok", "great", "perfect", "nice one"]:
        assert is_chitchat(q), f"expected {q!r} to be chitchat"


def test_recognizes_farewells_as_chitchat():
    for q in ["bye", "goodbye", "see you later"]:
        assert is_chitchat(q), f"expected {q!r} to be chitchat"


def test_does_not_misclassify_a_real_short_question_as_chitchat():
    # A short question is still a question -- must not be swept up just because it's brief.
    for q in ["what is 4sum", "explain this", "how does auth work", "is it copied"]:
        assert not is_chitchat(q), f"expected {q!r} to NOT be chitchat"


def test_farewell_gets_a_distinct_response_from_a_thanks_or_greeting():
    bye_response = chitchat_response("bye")
    thanks_response = chitchat_response("okay thanks")
    assert bye_response != thanks_response
    assert "see you" in bye_response.lower() or "around" in bye_response.lower()


async def test_answer_question_short_circuits_on_chitchat_without_calling_the_llm():
    llm = AsyncMock()

    answer, citations = await answer_question(llm, "okay thanks", [CHUNK])

    llm.complete.assert_not_called()
    assert citations == []
    assert "happy to help" in answer.lower()
