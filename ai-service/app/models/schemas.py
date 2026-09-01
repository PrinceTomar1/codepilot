"""
Pydantic request/response models. Field names use the exact casing required
by the API contract (camelCase on the wire via `alias`, since the Spring Boot
backend is the consumer), while Python code can keep snake_case attributes.
"""
from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

Severity = Literal["low", "medium", "high"]


class CamelModel(BaseModel):
    """Base model that (de)serializes camelCase on the wire."""

    model_config = ConfigDict(populate_by_name=True)


# ---------------------------------------------------------------------------
# /index
# ---------------------------------------------------------------------------
class FileInput(CamelModel):
    path: str
    language: str
    content: str


class IndexRequest(CamelModel):
    repository_id: str = Field(alias="repositoryId")
    files: list[FileInput]


class IndexResponse(CamelModel):
    repository_id: str = Field(alias="repositoryId")
    files_indexed: int = Field(alias="filesIndexed")
    chunks_created: int = Field(alias="chunksCreated")
    status: str = "COMPLETED"


# ---------------------------------------------------------------------------
# /query
# ---------------------------------------------------------------------------
class QaTurn(CamelModel):
    question: str
    answer: str


class QueryRequest(CamelModel):
    repository_id: str = Field(alias="repositoryId")
    question: str
    top_k: int = Field(default=8, alias="topK")
    # Prior turns in this conversation, oldest first -- lets follow-up questions like
    # "does it handle errors?" resolve "it" against what was just discussed.
    history: list[QaTurn] = Field(default_factory=list)


class Citation(CamelModel):
    file_path: str = Field(alias="filePath")
    start_line: int = Field(alias="startLine")
    end_line: int = Field(alias="endLine")
    snippet: str


class QueryResponse(CamelModel):
    answer: str
    citations: list[Citation]
    chunks_retrieved: int = Field(alias="chunksRetrieved")


# ---------------------------------------------------------------------------
# /search -- direct semantic/keyword code search, no LLM call: returns matched
# chunks straight from retrieval, for a dedicated search UI distinct from Q&A.
# ---------------------------------------------------------------------------
class SearchRequest(CamelModel):
    repository_id: str = Field(alias="repositoryId")
    query: str
    # Optional (not just defaulted): a Pydantic default only applies when the field is OMITTED
    # from the JSON body, not when it's present but explicitly null -- and the Java backend's
    # AiSearchRequest record serializes an absent topK as a literal "topK": null, not an omitted
    # key. A plain `int` field rejects that null outright, as a real 422.
    top_k: int | None = Field(default=None, alias="topK")


class SearchResult(CamelModel):
    file_path: str = Field(alias="filePath")
    language: str | None = None
    start_line: int = Field(alias="startLine")
    end_line: int = Field(alias="endLine")
    snippet: str
    symbol_name: str | None = Field(default=None, alias="symbolName")
    # "exact": found via keyword/symbol/filename match, no meaningful cosine distance to report.
    # "similarity": found via vector search; relevanceScore is populated (1 - distance, 0-1).
    match_type: Literal["exact", "similarity"] = Field(alias="matchType")
    relevance_score: float | None = Field(default=None, alias="relevanceScore")


class SearchResponse(CamelModel):
    results: list[SearchResult]


# ---------------------------------------------------------------------------
# /review
# ---------------------------------------------------------------------------
class ReviewFileInput(CamelModel):
    path: str
    diff: str = ""
    full_content: str = Field(default="", alias="fullContent")


class ReviewRequest(CamelModel):
    pull_request_id: str = Field(alias="pullRequestId")
    files: list[ReviewFileInput]


class Finding(CamelModel):
    file: str
    line: int | None = None
    severity: Severity = "medium"
    description: str
    suggestion: str = ""
    # A concrete, ready-to-apply fix -- the actual before/after code, not just prose advice.
    # Both optional: a finding like "missing test coverage" has no "original" broken code to show
    # (there's nothing wrong with the existing code, something is absent), so original_code stays
    # empty while fixed_code can still carry a suggested test snippet when practical.
    original_code: str | None = Field(default=None, alias="originalCode")
    fixed_code: str | None = Field(default=None, alias="fixedCode")


class ReviewFindings(CamelModel):
    bugs: list[Finding] = Field(default_factory=list)
    security: list[Finding] = Field(default_factory=list)
    code_smells: list[Finding] = Field(default_factory=list, alias="codeSmells")
    missing_tests: list[Finding] = Field(default_factory=list, alias="missingTests")
    performance: list[Finding] = Field(default_factory=list)


class ReviewResponse(CamelModel):
    summary: str
    findings: ReviewFindings


# ---------------------------------------------------------------------------
# /onboarding
# ---------------------------------------------------------------------------
class OnboardingRequest(CamelModel):
    repository_id: str = Field(alias="repositoryId")


class ImportantModule(CamelModel):
    path: str
    description: str


class OnboardingResponse(CamelModel):
    architecture_overview: str = Field(alias="architectureOverview")
    important_modules: list[ImportantModule] = Field(alias="importantModules")
    setup_instructions: str = Field(alias="setupInstructions")
    data_flow: str = Field(alias="dataFlow")
    read_first: list[str] = Field(alias="readFirst")


# ---------------------------------------------------------------------------
# /architecture
# ---------------------------------------------------------------------------
class ArchitectureRequest(CamelModel):
    repository_id: str = Field(alias="repositoryId")


class ArchitectureNode(CamelModel):
    id: str
    language: str | None = None


class ArchitectureEdge(CamelModel):
    source: str
    target: str


class ArchitectureResponse(CamelModel):
    nodes: list[ArchitectureNode]
    edges: list[ArchitectureEdge]


# ---------------------------------------------------------------------------
# /health
# ---------------------------------------------------------------------------
class HealthResponse(BaseModel):
    status: str = "ok"
