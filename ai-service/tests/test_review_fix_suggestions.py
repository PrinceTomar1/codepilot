"""
The code fixer feature: each review finding can now carry an actual before/after code fix
(originalCode/fixedCode), not just prose advice, so the frontend can render a real diff the user
reviews and copies -- rather than just being told in words what to change. These tests cover the
parsing side: agents ask the model for these two extra JSON fields and must parse them correctly,
including the cases where the model legitimately has no concrete original/fixed snippet to give
(e.g. a "missing test coverage" finding has no broken existing code to point at).
"""
from __future__ import annotations

from unittest.mock import AsyncMock

from app.agents.bug_agent import BugDetectionAgent
from app.agents.quality_agent import CodeQualityAgent
from app.models.schemas import ReviewFileInput

FILES = [ReviewFileInput(path="src/foo.py", diff="+bar()", full_content="def bar(): pass")]


async def test_base_agent_parses_original_and_fixed_code():
    llm = AsyncMock()
    llm.complete.return_value = """[
        {
            "file": "src/foo.py",
            "line": 3,
            "severity": "high",
            "description": "SQL injection via string concatenation",
            "suggestion": "Use a parameterized query",
            "originalCode": "query = \\"SELECT * FROM users WHERE id = \\" + user_id",
            "fixedCode": "query = \\"SELECT * FROM users WHERE id = %s\\"\\ncursor.execute(query, (user_id,))"
        }
    ]"""

    findings = await BugDetectionAgent().review(llm, FILES)

    assert len(findings) == 1
    assert findings[0].original_code == 'query = "SELECT * FROM users WHERE id = " + user_id'
    assert "cursor.execute" in findings[0].fixed_code


async def test_base_agent_treats_missing_fix_fields_as_none_not_empty_string():
    llm = AsyncMock()
    llm.complete.return_value = """[
        {"file": "src/foo.py", "line": 1, "severity": "medium", "description": "no fix given"}
    ]"""

    findings = await BugDetectionAgent().review(llm, FILES)

    assert findings[0].original_code is None
    assert findings[0].fixed_code is None


async def test_base_agent_treats_explicit_null_and_blank_fix_fields_as_none():
    llm = AsyncMock()
    llm.complete.return_value = """[
        {"file": "src/foo.py", "line": 1, "severity": "low", "description": "d",
         "originalCode": null, "fixedCode": "   "}
    ]"""

    findings = await BugDetectionAgent().review(llm, FILES)

    assert findings[0].original_code is None
    assert findings[0].fixed_code is None


async def test_quality_agent_parses_original_and_fixed_code():
    llm = AsyncMock()
    llm.complete.return_value = """[
        {
            "file": "src/foo.py",
            "line": 5,
            "category": "performance",
            "severity": "high",
            "description": "N+1 query in a loop",
            "suggestion": "Batch into one query",
            "originalCode": "for uid in ids:\\n    db.execute(f'SELECT * FROM orders WHERE user_id={uid}')",
            "fixedCode": "db.execute('SELECT * FROM orders WHERE user_id IN %s', (tuple(ids),))"
        }
    ]"""

    smells, perf = await CodeQualityAgent().review_categorized(llm, FILES)

    assert smells == []
    assert len(perf) == 1
    assert "IN %s" in perf[0].fixed_code
    assert perf[0].original_code is not None


async def test_missing_test_coverage_finding_can_have_a_fix_snippet_with_no_original():
    # The natural case for a "missing tests" finding: there's no existing broken code to point at
    # (originalCode is null), but the model can still offer a concrete new test as fixedCode.
    llm = AsyncMock()
    llm.complete.return_value = """[
        {
            "file": "src/foo.py",
            "line": null,
            "severity": "medium",
            "description": "No test covers the error path",
            "suggestion": "Add a test for the failure case",
            "originalCode": null,
            "fixedCode": "def test_bar_raises_on_invalid_input():\\n    with pytest.raises(ValueError):\\n        bar(None)"
        }
    ]"""

    findings = await BugDetectionAgent().review(llm, FILES)

    assert findings[0].original_code is None
    assert "def test_bar_raises_on_invalid_input" in findings[0].fixed_code
