"""
Code search (Phase 6 of the audit): a dedicated search UI distinct from the Q&A chat, returning
matched chunks directly with no LLM call -- file, snippet, line numbers, and a relevance score
where one is meaningful. build_search_results() is the pure conversion from hybrid-retrieval
chunks to the API response shape; the retrieval itself (vector/keyword search, merge priority) is
already covered by test_rag_hybrid_retrieval.py, so these tests focus on the match-type/score
labeling specifically.
"""
from __future__ import annotations

from app.models.schemas import SearchRequest
from app.services.rag import build_search_results
from app.services.vector_store import RetrievedChunk


def _chunk(path: str, distance: float, symbol: str | None = None) -> RetrievedChunk:
    return RetrievedChunk(
        file_path=path, language="python", start_line=1, end_line=5,
        content=f"content of {path}", distance=distance, symbol_name=symbol,
    )


def test_keyword_matched_result_is_labeled_exact_with_no_score():
    chunk = _chunk("src/auth.py", distance=0.0, symbol="authenticate")
    results = build_search_results(merged=[chunk], keyword_chunks=[chunk])

    assert results[0].match_type == "exact"
    assert results[0].relevance_score is None


def test_vector_only_result_is_labeled_similarity_with_a_score():
    chunk = _chunk("src/db.py", distance=0.3)
    results = build_search_results(merged=[chunk], keyword_chunks=[])

    assert results[0].match_type == "similarity"
    assert results[0].relevance_score == 0.7


def test_relevance_score_is_clamped_to_zero_one_range():
    # Cosine distance can exceed 1.0 for dissimilar vectors -- 1 - distance must not go negative.
    far_chunk = _chunk("src/unrelated.py", distance=1.8)
    results = build_search_results(merged=[far_chunk], keyword_chunks=[])

    assert results[0].relevance_score == 0.0


def test_preserves_file_path_lines_and_symbol_name():
    chunk = RetrievedChunk(
        file_path="src/user_service.py", language="python", start_line=10, end_line=25,
        content="def get_user(): ...", distance=0.2, symbol_name="get_user",
    )
    results = build_search_results(merged=[chunk], keyword_chunks=[])

    r = results[0]
    assert r.file_path == "src/user_service.py"
    assert r.start_line == 10
    assert r.end_line == 25
    assert r.symbol_name == "get_user"
    assert r.snippet == "def get_user(): ..."


def test_empty_input_produces_empty_results():
    assert build_search_results(merged=[], keyword_chunks=[]) == []


def test_result_order_matches_merged_order():
    a = _chunk("a.py", distance=0.1)
    b = _chunk("b.py", distance=0.2)
    results = build_search_results(merged=[a, b], keyword_chunks=[])

    assert [r.file_path for r in results] == ["a.py", "b.py"]


def test_request_accepts_an_explicit_null_top_k_not_just_an_omitted_one():
    # Real bug found live: the Java backend's AiSearchRequest record serializes an absent topK as
    # a literal "topK": null, not an omitted JSON key -- a plain `int` field with a default only
    # honors that default when the key is OMITTED, so an explicit null was rejected with a 422
    # ("Input should be a valid integer") even though the caller's intent was "use the default".
    request = SearchRequest.model_validate({"repositoryId": "r1", "query": "q", "topK": None})
    assert request.top_k is None

