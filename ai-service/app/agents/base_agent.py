"""
Base class for the four independent PR-review agents.

Each concrete agent supplies its own focused system prompt and calls the LLM
on its own -- there is no single "do everything" prompt. Agents are run
concurrently by the ReviewOrchestrator via asyncio.gather.

Structured output strategy: agents ask the model for a strict JSON array
matching the Finding schema, then parse it defensively. If parsing fails
(malformed JSON, wrapped in prose, etc.) we log the raw response and fall
back to an empty finding list rather than raising -- one agent's bad output
should never take down the whole review.
"""
from __future__ import annotations

import json
import logging
from abc import ABC, abstractmethod

from app.models.schemas import Finding, ReviewFileInput
from app.services.llm import UNTRUSTED_CONTENT_NOTICE, LLMClient, LLMNotConfiguredError, LLMRateLimitedError

logger = logging.getLogger("codepilot.agents")

JSON_INSTRUCTIONS = """Respond with ONLY a JSON array (no markdown fences, no commentary, no \
surrounding prose) of finding objects, each matching exactly this shape:

[
  {
    "file": "<file path, exactly as given>",
    "line": <line number as an integer, or null if not line-specific>,
    "severity": "low" | "medium" | "high",
    "description": "<what the issue is, specific and concrete>",
    "suggestion": "<concrete suggested fix or improvement, in prose>",
    "originalCode": "<the exact existing line(s) this finding is about, verbatim from the file, \
or null if there's no specific existing code to point at (e.g. something is missing entirely)>",
    "fixedCode": "<the corrected replacement for originalCode -- real, complete, syntactically \
valid code the reviewer could paste in directly, not a description of what to change. Keep it to \
just the changed lines plus the minimum surrounding context needed to be unambiguous, not the \
whole file. Use null if you cannot state a concrete fix (e.g. "add integration tests for this" \
with no single obvious snippet).>"
  }
]

If you find no issues in this category, respond with an empty JSON array: []"""


def _optional_str(value: object) -> str | None:
    """Coerces a JSON field the model may have left as null, omitted, or an empty string into a
    clean Optional[str] -- "the model gave no fix" and "the model gave an empty-string fix" are
    the same thing to the caller, and should look the same (None), not sometimes "" ."""
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _format_files_block(files: list[ReviewFileInput]) -> str:
    pieces = []
    for f in files:
        block = f"=== FILE: {f.path} ===\n"
        if f.diff:
            block += f"--- DIFF ---\n{f.diff}\n"
        if f.full_content:
            # Cap full file content to keep prompts a reasonable size.
            content = f.full_content
            if len(content) > 8000:
                content = content[:8000] + "\n... (truncated)"
            block += f"--- FULL FILE CONTENT ---\n{content}\n"
        pieces.append(block)
    return "\n".join(pieces)


def _extract_json_array(text: str) -> str | None:
    """Best-effort extraction of a JSON array from a model response that may include stray prose
    or markdown fences around the JSON.

    Tolerates truncation: a real, observed failure mode is the model hitting max_output_tokens
    mid-array on a file with many findings. The naive approach (find the last ']') then finds
    none and throws away EVERY finding, including ones the model had already finished writing
    before the cutoff. Instead this walks the text tracking brace depth (string/escape-aware) and
    keeps whichever complete top-level {...} objects it can find, discarding only the final
    incomplete one if the array never closes."""
    start = text.find("[")
    if start == -1:
        return None

    objects: list[str] = []
    depth = 0
    obj_start: int | None = None
    in_string = False
    escape = False
    closed = False
    for i in range(start + 1, len(text)):
        ch = text[i]
        if in_string:
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == '"':
                in_string = False
            continue
        if ch == '"':
            in_string = True
        elif ch == "{":
            if depth == 0:
                obj_start = i
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0 and obj_start is not None:
                objects.append(text[obj_start:i + 1])
                obj_start = None
        elif ch == "]" and depth == 0:
            closed = True
            break

    if not objects:
        # A genuinely empty array ("no issues found") is a normal, valid response -- distinct
        # from a truncated response with nothing salvageable, which should still fall through to
        # the caller's "could not locate JSON array" warning rather than look like a clean [].
        return "[]" if closed else None
    return "[" + ",".join(objects) + "]"


class BaseReviewAgent(ABC):
    #: short category name, used only for logging
    name: str = "base"

    @property
    @abstractmethod
    def system_prompt(self) -> str:
        raise NotImplementedError

    async def review(self, llm: LLMClient, files: list[ReviewFileInput]) -> list[Finding]:
        if not files:
            return []

        user_prompt = (
            f"{_format_files_block(files)}\n\n{JSON_INSTRUCTIONS}"
        )

        try:
            # 4096, not 2048: live testing on a file with several planted issues showed 2048 was
            # tight enough that the model got truncated mid-JSON-array, and (before the
            # _extract_json_array fix above) that meant losing every finding, not just the last one.
            raw = await llm.complete(
                system=self.system_prompt + UNTRUSTED_CONTENT_NOTICE, user=user_prompt, max_tokens=4096
            )
        except (LLMNotConfiguredError, LLMRateLimitedError):
            # Systemic failures, not this-agent-specific ones -- swallowing these would make the
            # review silently come back as "no issues found" instead of surfacing the real 503/429
            # to the caller. Let them propagate through asyncio.gather to the router.
            raise
        except Exception:
            logger.exception("%s agent: LLM call failed", self.name)
            return []

        return self._parse_findings(raw)

    def _parse_findings(self, raw: str) -> list[Finding]:
        candidate = _extract_json_array(raw)
        if candidate is None:
            logger.warning("%s agent: could not locate JSON array in response: %r", self.name, raw[:500])
            return []
        try:
            parsed = json.loads(candidate)
        except json.JSONDecodeError:
            logger.warning("%s agent: JSON decode failed, raw response: %r", self.name, raw[:500])
            return []

        if not isinstance(parsed, list):
            logger.warning("%s agent: expected a JSON array, got %r", self.name, type(parsed))
            return []

        findings: list[Finding] = []
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
                findings.append(
                    Finding(
                        file=str(item.get("file", "unknown")),
                        line=line,
                        severity=severity,  # type: ignore[arg-type]
                        description=str(item.get("description", "")).strip(),
                        suggestion=str(item.get("suggestion", "")).strip(),
                        original_code=_optional_str(item.get("originalCode")),
                        fixed_code=_optional_str(item.get("fixedCode")),
                    )
                )
            except Exception:
                logger.exception("%s agent: failed to coerce finding item %r", self.name, item)
                continue
        return findings
