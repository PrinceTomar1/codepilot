"""
A real bug found via live testing: given a file with several genuine planted issues (hardcoded
API key, SQL injection, N+1 query, a duplicated dead branch, missing tests), the security and
missing-tests agents came back with correct findings -- but the bug and quality agents' raw model
responses got cut off mid-array at the token limit, and the OLD _extract_json_array() (which just
did `text.rfind("]")`) found no closing bracket and returned None, silently discarding EVERY
finding from that agent, including ones the model had already finished writing before the cutoff.
The fix makes _extract_json_array() walk the text and keep whichever complete top-level {...}
objects it can find, so a truncated response still yields the findings that were actually
completed. These tests lock that in directly (no LLM needed) plus the well-formed/malformed paths
the original implementation already had to handle.
"""
from __future__ import annotations

from app.agents.base_agent import _extract_json_array


def test_extracts_a_well_formed_array():
    text = '[{"file": "a.py", "line": 1, "description": "x"}]'
    assert _extract_json_array(text) == text


def test_extracts_array_wrapped_in_markdown_fence():
    text = '```json\n[{"file": "a.py", "line": 1, "description": "x"}]\n```'
    import json
    result = _extract_json_array(text)
    assert json.loads(result) == [{"file": "a.py", "line": 1, "description": "x"}]


def test_salvages_complete_objects_from_a_truncated_array():
    # Real observed shape: valid first object, then cut off mid-second-object with no closing ].
    truncated = (
        '[\n'
        '  {"file": "a.py", "line": 13, "category": "performance", "description": "N+1 query"},\n'
        '  {"file": "a.py", "line": 22, "category": "code_smell", "desc'
    )
    import json
    result = _extract_json_array(truncated)
    parsed = json.loads(result)
    assert len(parsed) == 1
    assert parsed[0]["description"] == "N+1 query"


def test_salvages_multiple_complete_objects_before_a_truncated_final_one():
    truncated = (
        '[{"file": "a.py", "description": "first"},'
        '{"file": "b.py", "description": "second"},'
        '{"file": "c.py", "description": "thi'
    )
    import json
    result = _extract_json_array(truncated)
    parsed = json.loads(result)
    assert [p["description"] for p in parsed] == ["first", "second"]


def test_handles_braces_inside_string_values_correctly():
    # A description mentioning "{}" characters shouldn't confuse the brace-depth tracker.
    text = '[{"file": "a.py", "description": "uses {} placeholders and \\"quotes\\""}]'
    import json
    result = _extract_json_array(text)
    parsed = json.loads(result)
    assert parsed[0]["description"] == 'uses {} placeholders and "quotes"'


def test_returns_none_when_truncated_before_any_complete_object():
    truncated = '[\n  {"file": "a.py", "line": 1, "desc'
    assert _extract_json_array(truncated) is None


def test_returns_none_for_text_with_no_array_at_all():
    assert _extract_json_array("Sorry, I found no issues.") is None


def test_returns_empty_array_string_unmodified():
    assert _extract_json_array("[]") == "[]"
