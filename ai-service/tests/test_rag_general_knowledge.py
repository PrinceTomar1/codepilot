"""
Real bug: asking something with zero connection to the indexed codebase (e.g. "tell me police
number") correctly triggered the strict refusal -- but the user wanted the assistant to actually
help with clearly off-topic questions too, using general knowledge, rather than refuse outright.
The fix (see QUERY_SYSTEM_PROMPT's general-knowledge rule in rag.py) instructs the model to answer
from general knowledge but prefix the response with GENERAL_KNOWLEDGE_MARKER when it does. These
tests lock in the answer_question() side of that: the marker must get stripped from the visible
text (replaced with a clean italic label) and citations from the retrieved chunks -- which are
irrelevant to an off-topic answer -- must never get attached, since that would misleadingly imply
the repository was the source of a general-knowledge fact.
"""
from __future__ import annotations

from unittest.mock import AsyncMock

from app.services.rag import GENERAL_KNOWLEDGE_MARKER, NO_CONTEXT_ANSWER, answer_question
from app.services.vector_store import RetrievedChunk

CHUNK = RetrievedChunk(
    file_path="weather_app.py", language="python", start_line=1, end_line=5,
    content="import requests", distance=0.1,
)


async def test_general_knowledge_answer_has_no_citations():
    llm = AsyncMock()
    llm.complete.return_value = f"{GENERAL_KNOWLEDGE_MARKER}\n\nEmergency numbers vary by country."

    answer, citations = await answer_question(llm, "what's the police number?", [CHUNK])

    assert citations == []


async def test_general_knowledge_marker_is_kept_visible_but_styled_as_a_label():
    llm = AsyncMock()
    llm.complete.return_value = f"{GENERAL_KNOWLEDGE_MARKER}\n\nThe capital of France is Paris."

    answer, _ = await answer_question(llm, "what's the capital of France?", [CHUNK])

    assert "Paris" in answer
    # The label stays visible (that's the point -- clearly marked as not from the repo), just
    # wrapped as a markdown-italic label rather than left as raw instruction-shaped text.
    assert f"_{GENERAL_KNOWLEDGE_MARKER}_" in answer
    assert "general knowledge" in answer.lower()


async def test_general_knowledge_detection_is_case_insensitive_and_tolerates_leading_whitespace():
    llm = AsyncMock()
    llm.complete.return_value = f"  {GENERAL_KNOWLEDGE_MARKER.upper()}\n\nSome answer."

    answer, citations = await answer_question(llm, "unrelated question", [CHUNK])

    assert citations == []
    assert "Some answer." in answer


async def test_query_uses_zero_temperature_for_consistent_rule_following():
    # Real observation, caught TWICE independently ("reasons to skip classes", then "explain
    # code"): the exact same question non-deterministically got the strict refusal on one live
    # call and a correct grounded/general-knowledge answer on a retry, even after tightening to
    # 0.1. This is a classification decision (grounded / refuse / general knowledge), not creative
    # writing, so it runs at 0.0 -- there's no real creativity being traded away here, only
    # consistency being bought.
    llm = AsyncMock()
    llm.complete.return_value = "answer"

    await answer_question(llm, "some question", [CHUNK])

    assert llm.complete.call_args.kwargs["temperature"] == 0.0


async def test_query_requests_fast_mode_to_cut_thinking_latency():
    # Chatbot latency was traced to Gemini's internal "thinking" step -- answering from given
    # context is synthesis, not exploratory reasoning, so it doesn't need it.
    llm = AsyncMock()
    llm.complete.return_value = "answer"

    await answer_question(llm, "some question", [CHUNK])

    assert llm.complete.call_args.kwargs["fast"] is True


async def test_falls_back_to_a_real_general_knowledge_answer_when_the_first_pass_wrongly_refuses():
    # Real bug, confirmed live: "give me some details on nepal" got the flat refusal even though
    # QUERY_SYSTEM_PROMPT already instructs the model to use general knowledge for off-topic
    # questions -- a smaller model doesn't reliably self-classify this within one big multi-rule
    # prompt. The fix asks again with a simpler, single-purpose classifier before trusting a
    # refusal, then answers for real if that classifier agrees it's off-topic.
    llm = AsyncMock()
    llm.complete.side_effect = [
        NO_CONTEXT_ANSWER,           # the main grounded-answer pass refuses
        "OFFTOPIC",                  # the simpler fallback classifier disagrees with itself
        "Nepal is a landlocked country in South Asia, home to Mount Everest.",
    ]

    answer, citations = await answer_question(llm, "give me some details on nepal", [CHUNK])

    assert citations == []
    assert f"_{GENERAL_KNOWLEDGE_MARKER}_" in answer
    assert "Nepal" in answer
    assert llm.complete.call_count == 3


async def test_keeps_the_refusal_when_the_fallback_classifier_agrees_its_in_scope():
    # A genuinely ungrounded but IN-SCOPE question (about the code, just not covered by what's
    # indexed) must still refuse -- the fallback is only for wrongly-refused off-topic questions.
    llm = AsyncMock()
    llm.complete.side_effect = [NO_CONTEXT_ANSWER, "INSCOPE"]

    answer, citations = await answer_question(llm, "how does the payment webhook retry logic work", [CHUNK])

    assert answer == NO_CONTEXT_ANSWER
    assert citations == []
    assert llm.complete.call_count == 2


async def test_fallback_classifier_failure_keeps_the_original_refusal_rather_than_crashing():
    llm = AsyncMock()
    llm.complete.side_effect = [NO_CONTEXT_ANSWER, RuntimeError("provider unreachable")]

    answer, citations = await answer_question(llm, "some ungrounded question", [CHUNK])

    assert answer == NO_CONTEXT_ANSWER
    assert citations == []


async def test_normal_grounded_answer_still_gets_real_citations():
    llm = AsyncMock()
    llm.complete.return_value = "This imports requests (weather_app.py:1-5)."

    answer, citations = await answer_question(llm, "what does this import?", [CHUNK])

    assert len(citations) == 1
    assert citations[0].file_path == "weather_app.py"
