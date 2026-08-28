from __future__ import annotations

import json
import logging

from app.agents.base_agent import BaseReviewAgent, _extract_json_array, _format_files_block, _optional_str
from app.models.schemas import Finding, ReviewFileInput
from app.services.llm import UNTRUSTED_CONTENT_NOTICE, LLMClient, LLMNotConfiguredError, LLMRateLimitedError

logger = logging.getLogger("codepilot.agents")

_QUALITY_JSON_INSTRUCTIONS = """Respond with ONLY a JSON array (no markdown fences, no \
commentary, no surrounding prose) of finding objects, each matching exactly this shape:

[
  {
    "file": "<file path, exactly as given>",
    "line": <line number as an integer, or null if not line-specific>,
    "category": "code_smell" | "performance",
    "severity": "low" | "medium" | "high",
    "description": "<what the issue is, specific and concrete>",
    "suggestion": "<concrete suggested fix or improvement, in prose>",
    "originalCode": "<the exact existing line(s) this finding is about, verbatim from the file, \
or null if there's no specific existing code to point at>",
    "fixedCode": "<the corrected replacement for originalCode -- real, complete, syntactically \
valid code, not a description of the change. Keep it to just the changed lines plus the minimum \
context needed to be unambiguous. Use null if you cannot state one concrete snippet.>"
  }
]

Use "category": "performance" for anything performance/efficiency related, and "code_smell" \
for everything else (duplication, complexity, naming, dead code, maintainability, etc). If you \
find no issues, respond with an empty JSON array: []"""


class CodeQualityAgent(BaseReviewAgent):
    """
    Covers both code smells and performance concerns in one LLM call (they're
    closely related maintainability judgments), but tags each finding with a
    category so the orchestrator can route it into the response's separate
    `codeSmells` and `performance` finding lists.
    """

    name = "code_quality"

    @property
    def system_prompt(self) -> str:
        return """You are a pragmatic senior engineer reviewing a pull request for code quality: \
code smells (duplicated logic, overly long/complex functions, deep nesting, poor naming, dead \
code, magic numbers/strings, tight coupling), performance issues (unnecessary O(n^2)+ work, \
N+1 queries, redundant computation/allocations in hot paths, missing pagination/streaming for \
large data), and maintainability concerns.

Do not report security vulnerabilities, functional bugs, or missing tests -- those are handled \
by other reviewers. Be specific and constructive; prefer a small number of high-value findings \
over a long list of nitpicks. Only report issues you can actually see evidence of in the given \
file content or diff."""

    async def review_categorized(
        self, llm: LLMClient, files: list[ReviewFileInput]
    ) -> tuple[list[Finding], list[Finding]]:
        """Returns (code_smells, performance)."""
        if not files:
            return [], []

        user_prompt = f"{_format_files_block(files)}\n\n{_QUALITY_JSON_INSTRUCTIONS}"
        try:
            # 4096: this agent covers TWO categories (code smells + performance) in one response,
            # so it's the most truncation-prone of the four -- directly observed live, cut off
            # mid-array at the 2048 default. See base_agent.py's review() for the same fix.
            raw = await llm.complete(
                system=self.system_prompt + UNTRUSTED_CONTENT_NOTICE, user=user_prompt, max_tokens=4096
            )
        except (LLMNotConfiguredError, LLMRateLimitedError):
            # Systemic failures, not this-agent-specific ones -- let them propagate so the router
            # returns a real 503/429 instead of a silently-empty "no issues found" review.
            raise
        except Exception:
            logger.exception("quality agent: LLM call failed")
            return [], []

        candidate = _extract_json_array(raw)
        if candidate is None:
            logger.warning("quality agent: could not locate JSON array in response: %r", raw[:500])
            return [], []
        try:
            parsed = json.loads(candidate)
        except json.JSONDecodeError:
            logger.warning("quality agent: JSON decode failed, raw response: %r", raw[:500])
            return [], []
        if not isinstance(parsed, list):
            return [], []

        smells: list[Finding] = []
        perf: list[Finding] = []
        for item in parsed:
            if not isinstance(item, dict):
                continue
            try:
                severity = str(item.get("severity", "medium")).lower()
                if severity not in ("low", "medium", "high"):
                    severity = "medium"
                line = item.get("line")
                if line is not None:
                    try:
                        line = int(line)
                    except (TypeError, ValueError):
                        line = None
                finding = Finding(
                    file=str(item.get("file", "unknown")),
                    line=line,
                    severity=severity,  # type: ignore[arg-type]
                    description=str(item.get("description", "")).strip(),
                    suggestion=str(item.get("suggestion", "")).strip(),
                    original_code=_optional_str(item.get("originalCode")),
                    fixed_code=_optional_str(item.get("fixedCode")),
                )
                category = str(item.get("category", "code_smell")).lower()
                if category == "performance":
                    perf.append(finding)
                else:
                    smells.append(finding)
            except Exception:
                logger.exception("quality agent: failed to coerce finding item %r", item)
                continue
        return smells, perf
