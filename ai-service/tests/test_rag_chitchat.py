"""
Five real bugs found in live testing, all variations on one underlying shape: the strict "only
answer from context, otherwise refuse" rule was firing on questions that WERE actually
answerable, just not via a verbatim quote from the indexed code.

1. "hey" / "heya" returned the blunt refusal instead of a greeting response -- a plain greeting
   isn't a code question at all.
2. "tell me how to run this project" returned the refusal even though the retrieved context
   included the file's own `import PyQt5` / `import requests` lines -- inferring "install those
   and run `python <file>.py`" from visible imports is a grounded inference, not a guess, but the
   original prompt only credited the model for verbatim-quotable facts.
3. "rate this project out of 10" returned the refusal even though the model had real, specific
   material to base an opinion on (a hardcoded API key it had already found, single-file
   structure, etc.) -- the prompt only credited the model for stating plain facts, not for a
   grounded qualitative judgment, so it refused a legitimately answerable subjective question.
4. "suggest me a better project" returned the refusal -- genuinely ambiguous between "improve
   this project" and "recommend a different project," and the model defaulted to treating the
   ambiguity itself as grounds to refuse, rather than reading it the way any human would given
   the whole conversation is about this one repository.
5. "tell me police number" returned the refusal -- but this one, unlike the first four, is
   genuinely NOT about the codebase at all. The fix isn't another "actually this counts as
   grounded" carve-out; it's a deliberate, user-confirmed product decision to let the assistant
   answer clearly off-topic questions from its own general knowledge instead of refusing, as long
   as the answer is visibly labeled as general knowledge (not sourced from the repo) so it can
   never be confused with a grounded claim about the actual code.

QUERY_SYSTEM_PROMPT now carves out all five cases explicitly while keeping the strict grounding
rule (and the exact refusal wording) for genuinely ungroundable in-scope questions. Can't be
verified against a live model here (no network calls in this suite), so this locks in the
instruction text itself; see test_rag_general_knowledge.py for the citation-suppression behavior
in answer_question(), which IS testable without a live model (mocked LLM).
"""
from __future__ import annotations

from app.services.rag import NO_CONTEXT_ANSWER, QUERY_SYSTEM_PROMPT


def test_prompt_carves_out_greetings_from_the_refusal_rule():
    assert "greeting" in QUERY_SYSTEM_PROMPT.lower()
    assert "chitchat" in QUERY_SYSTEM_PROMPT.lower()
    assert "Do NOT use the \"not enough information\" refusal" in QUERY_SYSTEM_PROMPT


def test_prompt_permits_reasonable_inference_from_visible_imports_for_setup_questions():
    assert "how do I run this" in QUERY_SYSTEM_PROMPT
    assert "import requests" in QUERY_SYSTEM_PROMPT
    assert "PyQt5" in QUERY_SYSTEM_PROMPT
    # Still must not license inventing specifics the context doesn't support.
    assert "don't invent specifics" in QUERY_SYSTEM_PROMPT


def test_prompt_permits_grounded_subjective_opinions():
    assert "rate this" in QUERY_SYSTEM_PROMPT
    assert "hardcoded API key" in QUERY_SYSTEM_PROMPT
    # Still must not license an ungrounded rating -- it has to cite real observations.
    assert "with no supporting evidence from the context is still a guess" in QUERY_SYSTEM_PROMPT


def test_prompt_resolves_ambiguous_phrasing_toward_the_in_scope_reading():
    assert "suggest me a better project" in QUERY_SYSTEM_PROMPT
    assert "should be read as being about this codebase" in QUERY_SYSTEM_PROMPT


def test_prompt_permits_labeled_general_knowledge_answers_for_off_topic_questions():
    from app.services.rag import GENERAL_KNOWLEDGE_MARKER

    assert "police number" in QUERY_SYSTEM_PROMPT
    assert GENERAL_KNOWLEDGE_MARKER in QUERY_SYSTEM_PROMPT
    # Must still hedge on location-dependent facts rather than assert one universal answer.
    assert "vary by place" in QUERY_SYSTEM_PROMPT


def test_prompt_still_requires_the_exact_refusal_wording_for_real_questions():
    # The carve-outs must not weaken grounding for actual code questions -- the exact refusal
    # phrase (which answer_question() also matches on to strip misleading citations) must still
    # be mandated somewhere in the prompt, specifically for in-scope-but-uncovered questions (as
    # opposed to genuinely off-topic ones, which now get a general-knowledge answer instead).
    assert NO_CONTEXT_ANSWER in QUERY_SYSTEM_PROMPT
    assert "not a fact, not a reasonable inference, not even a groundable opinion" in QUERY_SYSTEM_PROMPT
