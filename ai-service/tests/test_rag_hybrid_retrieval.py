"""
Hybrid retrieval: extract_keywords/merge_retrieved_chunks/adaptive_top_k are the pure-function
core of supplementing vector similarity search with keyword/symbol/filename exact-match search
(see keyword_search() in vector_store.py for the DB side, and query.py for orchestration). These
tests cover them in isolation, without needing a database.
"""
from __future__ import annotations

from app.services.rag import (
    adaptive_top_k,
    extract_keywords,
    merge_retrieved_chunks,
    prioritize_representative_chunks,
)
from app.services.vector_store import RetrievedChunk


def _chunk(path: str, start: int, end: int, distance: float, symbol: str | None = None) -> RetrievedChunk:
    return RetrievedChunk(
        file_path=path, language="python", start_line=start, end_line=end,
        content=f"content of {path}:{start}-{end}", distance=distance, symbol_name=symbol,
    )


def test_extract_keywords_finds_digit_led_identifiers():
    # Real bug found live: every existing pattern requires a LEADING LETTER, so a question like
    # "explain the approach used in 4SUM" extracted only ["approach", "used"] -- completely
    # missing "4SUM" itself -- on a repo whose actual file is `4sum.cpp`. This naming convention
    # (2Sum, 3Sum, 4Sum, 132Pattern...) is common specifically in DSA/LeetCode-style repos, not a
    # contrived edge case. Confirmed live: retrieval never surfaced 4sum.cpp until this was fixed.
    keywords = extract_keywords("explain the approach he has used in 4SUM")
    assert "4SUM" in keywords


def test_extract_keywords_digit_led_identifier_ignores_plain_numbers():
    # A bare number ("42", "100") is meaningless as a search keyword -- unlike "4SUM", it has no
    # letters at all, so it must NOT be swept in just because it starts with a digit.
    keywords = extract_keywords("line 42 has the answer, which is 100")
    assert "42" not in keywords
    assert "100" not in keywords


def test_extract_keywords_finds_filenames():
    keywords = extract_keywords("what does weather_app.py do?")
    assert "weather_app.py" in keywords


def test_extract_keywords_finds_camel_case_identifiers():
    keywords = extract_keywords("where is calculateTotal called from?")
    assert "calculateTotal" in keywords


def test_extract_keywords_finds_snake_case_identifiers():
    keywords = extract_keywords("explain fetch_weather_data")
    assert "fetch_weather_data" in keywords


def test_extract_keywords_finds_pascal_case_class_names():
    keywords = extract_keywords("what does the WeatherClient class do?")
    assert "WeatherClient" in keywords


def test_extract_keywords_drops_stopwords_and_short_words():
    keywords = extract_keywords("what is the purpose of this file")
    assert "what" not in [k.lower() for k in keywords]
    assert "the" not in [k.lower() for k in keywords]
    assert "purpose" in keywords


def test_extract_keywords_dedupes_case_insensitively():
    keywords = extract_keywords("Widget widget WIDGET panel")
    lowered = [k.lower() for k in keywords]
    assert lowered.count("widget") == 1


def test_extract_keywords_caps_at_eight():
    question = " ".join(f"identifier{i}" for i in range(20))
    assert len(extract_keywords(question)) <= 8


def test_extract_keywords_empty_for_pure_greeting():
    assert extract_keywords("hey there") == [] or all(
        w not in ("hey", "there") for w in extract_keywords("hey there")
    )


def test_adaptive_top_k_boosts_broad_questions():
    assert adaptive_top_k("explain the architecture of this project", 8) == 15


def test_adaptive_top_k_never_reduces_an_explicit_larger_request():
    assert adaptive_top_k("give me a summary of this repo", 20) == 20


def test_adaptive_top_k_leaves_narrow_questions_unchanged():
    assert adaptive_top_k("where is calculateTotal defined?", 8) == 8


def test_merge_prioritizes_chunks_found_by_both_methods():
    both = _chunk("src/a.py", 1, 10, distance=0.5)
    vector_only = _chunk("src/b.py", 1, 10, distance=0.1)
    keyword_only = _chunk("src/c.py", 1, 10, distance=0.0)

    merged = merge_retrieved_chunks(
        vector_chunks=[both, vector_only],
        keyword_chunks=[both, keyword_only],
        limit=10,
    )

    assert merged[0].file_path == "src/a.py"


def test_merge_deduplicates_by_file_and_line_range():
    same_span_a = _chunk("src/a.py", 1, 10, distance=0.5)
    same_span_b = _chunk("src/a.py", 1, 10, distance=0.5)

    merged = merge_retrieved_chunks(vector_chunks=[same_span_a], keyword_chunks=[same_span_b], limit=10)

    assert len(merged) == 1


def test_merge_orders_vector_only_chunks_by_distance():
    far = _chunk("src/far.py", 1, 10, distance=0.9)
    close = _chunk("src/close.py", 1, 10, distance=0.1)

    merged = merge_retrieved_chunks(vector_chunks=[far, close], keyword_chunks=[], limit=10)

    assert [c.file_path for c in merged] == ["src/close.py", "src/far.py"]


def test_merge_respects_limit():
    vector_chunks = [_chunk(f"src/v{i}.py", 1, 10, distance=i / 10) for i in range(5)]
    keyword_chunks = [_chunk(f"src/k{i}.py", 1, 10, distance=0.0) for i in range(5)]

    merged = merge_retrieved_chunks(vector_chunks, keyword_chunks, limit=4)

    assert len(merged) == 4


def test_merge_handles_empty_inputs():
    assert merge_retrieved_chunks([], [], limit=10) == []


def test_adaptive_top_k_recognizes_explain_the_code_as_broad():
    # Real bug found live: "explain the code" wasn't in the broad-question marker set even though
    # it's semantically identical to "explain the project"/"explain the repo", which already were.
    assert adaptive_top_k("explain the code", 8) == 15
    assert adaptive_top_k("can you explain the codebase to me", 8) == 15


def test_adaptive_top_k_recognizes_the_general_broad_question_shape_not_just_exact_phrases():
    # Real bug found live TWICE: the first fix added "explain the code" as an exact marker, then
    # the very next report was "explain code" (no article) -- a different phrasing of the
    # identical request that a hardcoded phrase list can never fully enumerate. Replaced with a
    # regex over the general verb+object SHAPE of a broad question instead of a growing list.
    broad_phrasings = [
        "explain code",
        "explain codebase",
        "describe the code",
        "describe this codebase",
        "tell me about this project",
        "walk through this project",
        "what does this code do",
        "what does the codebase do",
    ]
    for question in broad_phrasings:
        assert adaptive_top_k(question, 8) == 15, f"expected broad detection for {question!r}"


def test_adaptive_top_k_still_correctly_leaves_narrow_explain_requests_alone():
    # The object matters, not just the verb: "explain this class" is a genuinely narrow, specific
    # request (about one class), not a repo-wide one -- must not be swept up by the broad pattern
    # just because it starts with "explain".
    assert adaptive_top_k("explain this class", 8) == 8
    assert adaptive_top_k("explain this function to me", 8) == 8
    assert adaptive_top_k("what does the login function do", 8) == 8


def test_prioritize_representative_chunks_puts_readme_and_entry_points_first():
    candidates = [
        _chunk("src/deeply/nested/util.ts", 1, 5, distance=0.0),
        _chunk("README.md", 1, 5, distance=0.0),
        _chunk("src/index.ts", 1, 5, distance=0.0),
        _chunk("package.json", 1, 5, distance=0.0),
    ]

    result = prioritize_representative_chunks(candidates, limit=4)

    paths = [c.file_path for c in result]
    assert paths[0] in ("README.md", "package.json", "src/index.ts")
    assert "src/deeply/nested/util.ts" == paths[-1]


def test_prioritize_representative_chunks_respects_limit():
    candidates = [_chunk(f"src/f{i}.py", 1, 5, distance=0.0) for i in range(20)]
    assert len(prioritize_representative_chunks(candidates, limit=8)) == 8


def test_prioritize_representative_chunks_handles_empty_input():
    assert prioritize_representative_chunks([], limit=8) == []
