"""
Language-aware-ish code chunking.

Rather than naive fixed-size windows, we try to split files along semantic
boundaries (function / method / class / component declarations, or markdown
headings) so each chunk is a coherent, retrievable unit. Any chunk that ends
up larger than MAX_CHUNK_LINES is further split; any file whose language we
don't have a heuristic for falls back to an overlapping sliding window.

Each boundary regex also captures the symbol name (class/function/method) it
matched -- previously detected only to decide *where* to split, then thrown
away. Surfacing it as `Chunk.symbol_name` lets retrieval do exact/keyword
symbol lookups ("where is calculateTotal defined") as a supplement to vector
similarity, which can rank an exact name match poorly under a bag-of-words
local embedding provider.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

MAX_CHUNK_LINES = 150
FALLBACK_WINDOW = 80
FALLBACK_OVERLAP = 10

JAVA_LIKE = {"java", "kotlin", "csharp", "c#", "cs"}
C_STYLE_BRACE_LANGS = {"java", "kotlin", "csharp", "c#", "cs", "javascript", "typescript",
                        "jsx", "tsx", "js", "ts", "go", "c", "cpp", "c++", "rust"}
JS_LIKE = {"javascript", "typescript", "jsx", "tsx", "js", "ts"}
MARKDOWN_LIKE = {"markdown", "md"}
PYTHON_LIKE = {"python", "py"}


@dataclass
class Chunk:
    file_path: str
    language: str
    start_line: int  # 1-indexed, inclusive
    end_line: int  # 1-indexed, inclusive
    content: str
    symbol_name: str | None = None


# --- boundary-detection regexes (each has a "name" capture group) --------------

# Java/Kotlin/C#: class/interface/enum declarations and method signatures.
_JAVA_BOUNDARY_RE = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*"  # annotations
    r"(?:(?:public|private|protected|static|final|abstract|synchronized|default|native|"
    r"internal|override|open|sealed)\s+)*"
    r"(?:(?:class|interface|enum|record)\s+(?P<name1>\w+)|"
    r"(?:[\w<>\[\],\?\s]+?)\s+(?P<name2>\w+)\s*\([^;{]*\)\s*(?:throws\s+[\w,\s]+)?\s*\{)",
    re.MULTILINE,
)

# JS/TS/React: function decls, arrow-function consts, class decls, components.
_JS_BOUNDARY_RE = re.compile(
    r"^\s*(?:export\s+(?:default\s+)?)?"
    r"(?:async\s+)?"
    r"(?:function\s*\*?\s*(?P<name1>\w*)\s*\(|"
    r"class\s+(?P<name2>\w+)|"
    r"const\s+(?P<name3>[A-Za-z_$][\w$]*)\s*(?::\s*[^=]+)?=\s*(?:async\s*)?\([^)]*\)\s*(?::[^=]+)?=>|"
    r"const\s+(?P<name4>[A-Za-z_$][\w$]*)\s*=\s*(?:async\s+)?function)",
    re.MULTILINE,
)

# Python: def/class at any indent level (methods included).
_PYTHON_BOUNDARY_RE = re.compile(
    r"^\s*(?:async\s+)?(?:def|class)\s+(?P<name1>\w+)",
    re.MULTILINE,
)

_MD_HEADING_RE = re.compile(r"^(#{1,6})\s+(?P<name1>.+)$", re.MULTILINE)


def _boundary_name(m: re.Match) -> str | None:
    for group_name in ("name1", "name2", "name3", "name4"):
        try:
            value = m.group(group_name)
        except IndexError:
            continue
        if value:
            return value.strip()
    return None


def _split_by_line_offsets(
    lines: list[str], offsets_with_names: list[tuple[int, str | None]]
) -> list[tuple[int, int, str | None]]:
    """Given sorted 0-indexed line-offsets (each with the symbol name detected there) where new
    sections start, return (start_line, end_line, symbol_name) 1-indexed-inclusive triples
    covering the whole file."""
    by_offset: dict[int, str | None] = dict(offsets_with_names)
    offsets = sorted(by_offset.keys())
    if not offsets or offsets[0] != 0:
        offsets = [0] + offsets
        by_offset.setdefault(0, None)

    spans = []
    for i, start in enumerate(offsets):
        end = offsets[i + 1] - 1 if i + 1 < len(offsets) else len(lines) - 1
        if end >= start:
            spans.append((start + 1, end + 1, by_offset.get(start)))
    return spans


def _find_boundaries(content: str, pattern: re.Pattern) -> list[tuple[int, str | None]]:
    results: list[tuple[int, str | None]] = []
    for m in pattern.finditer(content):
        line_idx = content.count("\n", 0, m.start())
        results.append((line_idx, _boundary_name(m)))
    return results


def _sliding_window(lines: list[str], window: int, overlap: int) -> list[tuple[int, int, str | None]]:
    spans = []
    if not lines:
        return spans
    step = max(window - overlap, 1)
    start = 0
    n = len(lines)
    while start < n:
        end = min(start + window, n)
        spans.append((start + 1, end, None))  # 1-indexed inclusive
        if end == n:
            break
        start += step
    return spans


def _cap_span(lines: list[str], start: int, end: int, symbol_name: str | None) -> list[tuple[int, int, str | None]]:
    """Split an oversized (start,end) 1-indexed span into <= MAX_CHUNK_LINES pieces. Only the
    first piece keeps the symbol name -- it's the one that actually starts at the declaration."""
    span_len = end - start + 1
    if span_len <= MAX_CHUNK_LINES:
        return [(start, end, symbol_name)]
    spans = []
    cur = start
    first = True
    while cur <= end:
        piece_end = min(cur + MAX_CHUNK_LINES - 1, end)
        spans.append((cur, piece_end, symbol_name if first else None))
        cur = piece_end + 1
        first = False
    return spans


def chunk_file(file_path: str, language: str, content: str) -> list[Chunk]:
    lang = (language or "").strip().lower()
    lines = content.splitlines()
    if not lines:
        return []

    spans: list[tuple[int, int, str | None]] = []

    if lang in JAVA_LIKE:
        offsets = _find_boundaries(content, _JAVA_BOUNDARY_RE)
        spans = _split_by_line_offsets(lines, offsets)
    elif lang in JS_LIKE:
        offsets = _find_boundaries(content, _JS_BOUNDARY_RE)
        spans = _split_by_line_offsets(lines, offsets)
    elif lang in PYTHON_LIKE:
        offsets = _find_boundaries(content, _PYTHON_BOUNDARY_RE)
        spans = _split_by_line_offsets(lines, offsets)
    elif lang in MARKDOWN_LIKE:
        offsets = _find_boundaries(content, _MD_HEADING_RE)
        spans = _split_by_line_offsets(lines, offsets)

    # If heuristic found nothing useful (e.g. unrecognized language, or a file
    # with no top-level declarations detected), fall back to sliding window.
    if not spans or len(spans) <= 1 and len(lines) > MAX_CHUNK_LINES:
        if not spans:
            spans = _sliding_window(lines, FALLBACK_WINDOW, FALLBACK_OVERLAP)

    # Cap any oversized chunk (e.g. a huge function body).
    capped: list[tuple[int, int, str | None]] = []
    for start, end, name in spans:
        capped.extend(_cap_span(lines, start, end, name))

    chunks: list[Chunk] = []
    for start, end, name in capped:
        text = "\n".join(lines[start - 1:end])
        if text.strip():
            chunks.append(Chunk(file_path=file_path, language=lang, start_line=start,
                                 end_line=end, content=text, symbol_name=name))
    return chunks
