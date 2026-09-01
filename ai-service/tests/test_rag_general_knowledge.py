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

from app.services.llm import LLMRateLimitedError
from app.services.rag import (
    GENERAL_KNOWLEDGE_MARKER,
    NO_CONTEXT_ANSWER,
    _fallback_answer_from_chunks,
    answer_question,
)
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
    # Real bug: "give me some details on nepal" got the flat refusal even though
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


async def test_falls_back_to_a_chunk_listing_when_both_the_main_pass_and_the_retry_refuse():
    # Real bug, reproduced live: the corrective retry (next test) resolves most wrong refusals,
    # but it's still a second roll of the same non-deterministic dice -- on a stubborn phrasing
    # ("explain me the code line by line") it refused TWICE in a row in production, even though
    # in-scope and non-empty-context were already confirmed by this point. Showing the user a
    # dead-end refusal at that point would be actively wrong, not just unhelpful -- so the final
    # fallback is deterministic and doesn't call the model at all, which means it structurally
    # cannot refuse.
    llm = AsyncMock()
    llm.complete.side_effect = [NO_CONTEXT_ANSWER, "INSCOPE", NO_CONTEXT_ANSWER]

    answer, citations = await answer_question(llm, "how does the payment webhook retry logic work", [CHUNK])

    assert answer != NO_CONTEXT_ANSWER
    assert "weather_app.py" in answer
    # Still genuinely grounded (every line in the fallback answer comes from a real retrieved
    # chunk), so unlike the true refusal case, citations ARE attached here.
    assert len(citations) == 1
    assert citations[0].file_path == "weather_app.py"
    assert llm.complete.call_count == 3


async def test_retries_once_and_uses_the_real_answer_when_the_first_pass_wrongly_refuses_in_scope():
    # Real bug, reproduced live against a real indexed repository: "explain me the code line by
    # line" retrieved 23 real, relevant chunks, yet the main pass still returned the flat refusal
    # -- the model over-generalized "I can't do this literally" (an exhaustive line-by-line
    # account of an entire multi-file repo) into "I have no basis at all," which wasn't true. A
    # retry with a corrective nudge (still in-context, still grounded) produced a real cited
    # answer. This is a *different* recovery path from the general-knowledge fallback above: the
    # question stays in-scope the whole time, there's no GENERAL_KNOWLEDGE_MARKER involved, and
    # the retrieved chunks' citations are still valid, since the retry answer is still grounded in
    # them.
    llm = AsyncMock()
    llm.complete.side_effect = [
        NO_CONTEXT_ANSWER,                              # the main grounded-answer pass refuses
        "INSCOPE",                                      # the fallback classifier: this IS about the repo
        "This imports requests (weather_app.py:1-5).",  # the retry succeeds with a real answer
    ]

    answer, citations = await answer_question(llm, "explain me the code line by line", [CHUNK])

    assert answer == "This imports requests (weather_app.py:1-5)."
    assert len(citations) == 1
    assert citations[0].file_path == "weather_app.py"
    assert llm.complete.call_count == 3
    # The retry must actually carry the corrective nudge, not just repeat the identical call --
    # otherwise it's just re-rolling the dice rather than steering the model away from the refusal.
    retry_call = llm.complete.call_args_list[2]
    assert "having no basis to answer" in retry_call.kwargs["system"]


async def test_fallback_classifier_failure_does_not_crash_and_still_reaches_the_chunk_listing():
    llm = AsyncMock()
    llm.complete.side_effect = [NO_CONTEXT_ANSWER, RuntimeError("provider unreachable"), NO_CONTEXT_ANSWER]

    answer, citations = await answer_question(llm, "some ungrounded question", [CHUNK])

    assert answer != NO_CONTEXT_ANSWER
    assert "weather_app.py" in answer
    assert len(citations) == 1


async def test_rate_limit_on_the_main_pass_falls_back_to_the_chunk_listing_without_wasting_more_calls():
    # Real bug, hit live: a free-tier LLM quota (Gemini's 20 requests/day) runs out under normal
    # testing/demo usage -- before this fix, a rate-limited call propagated all the way out as a
    # raw 429 with no chatbot answer at all. It should degrade to the same grounded, non-LLM
    # fallback used for a wrongly-refused question, and it should do so on the FIRST rate-limit
    # error rather than burning two more (already-exhausted) quota units on the off-topic-check/
    # retry dance, which would only hit the identical error again.
    llm = AsyncMock()
    llm.complete.side_effect = LLMRateLimitedError("quota exceeded")

    answer, citations = await answer_question(llm, "explain me the code line by line", [CHUNK])

    assert answer != NO_CONTEXT_ANSWER
    assert "weather_app.py" in answer
    assert len(citations) == 1
    assert llm.complete.call_count == 1


async def test_rate_limit_during_refusal_recovery_still_falls_back_gracefully():
    # Same reasoning as the main-pass case above, but for a rate limit hit AFTER the main pass
    # already refused and the off-topic classifier confirmed it's in-scope -- i.e. during the
    # corrective-retry call specifically, not the very first call.
    llm = AsyncMock()
    llm.complete.side_effect = [NO_CONTEXT_ANSWER, "INSCOPE", LLMRateLimitedError("quota exceeded")]

    answer, citations = await answer_question(llm, "some question", [CHUNK])

    assert answer != NO_CONTEXT_ANSWER
    assert "weather_app.py" in answer
    assert len(citations) == 1


def test_fallback_answer_groups_multiple_chunks_from_the_same_file_into_one_bullet():
    chunks = [
        RetrievedChunk(file_path="src/app.py", language="python", start_line=1, end_line=10,
                        content="a", distance=0.1),
        RetrievedChunk(file_path="src/app.py", language="python", start_line=40, end_line=55,
                        content="b", distance=0.2),
        RetrievedChunk(file_path="src/utils.py", language="python", start_line=5, end_line=8,
                        content="c", distance=0.3),
    ]

    answer = _fallback_answer_from_chunks(chunks)

    assert answer.count("src/app.py") == 1
    assert "1-10" in answer and "40-55" in answer
    assert "src/utils.py" in answer


async def test_normal_grounded_answer_still_gets_real_citations():
    llm = AsyncMock()
    llm.complete.return_value = "This imports requests (weather_app.py:1-5)."

    answer, citations = await answer_question(llm, "what does this import?", [CHUNK])

    assert len(citations) == 1
    assert citations[0].file_path == "weather_app.py"
