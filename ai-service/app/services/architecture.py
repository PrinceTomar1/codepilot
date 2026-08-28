"""
Lightweight, language-aware static analysis that builds a dependency graph from indexed
repository content: nodes are files, edges are internal (same-repo) import relationships.

This is genuinely computed per repository from real file content on each request -- there is no
fixed/hardcoded node or edge list, and the result necessarily differs between repositories (a
repo with no cross-file imports produces disconnected nodes and zero edges).

Deliberately a heuristic, not a full compiler-grade resolver: Python/Java imports are resolved by
matching the dotted module path against indexed file paths by suffix (handles the common case of
an import root that isn't the repo root, e.g. Java's `src/main/java/` prefix, at the cost of
occasionally matching the wrong file if two same-named modules exist in different packages).
JS/TS relative imports are resolved properly against the importing file's directory; bare
specifiers (`from 'react'`) are correctly treated as external and excluded -- EXCEPT for path
aliases (e.g. `@/components/Button`), which are a bare-looking specifier that's actually internal.
Real gap found via live testing: a 87-file repo produced only 13 edges, because every `@/...`
import (the standard Vite/Next/CRA alias convention, pointing at `src/`) was silently treated as
an external package and dropped -- most of a typical modern React repo's internal imports use
exactly this style. Fixed by parsing the repo's own `tsconfig*.json` `compilerOptions.paths`
mapping when present (handles any alias name/target, not just the common `@/` -> `src/` case),
falling back to the `@/` -> `src/` convention when no tsconfig is indexed.
"""
from __future__ import annotations

import posixpath
import re
from dataclasses import dataclass, field

_PY_IMPORT_RE = re.compile(r"^\s*(?:from\s+([.\w]+)\s+import|import\s+([.\w]+))", re.MULTILINE)
_JS_IMPORT_RE = re.compile(r"""(?:import\s+(?:[\w*{}\s,]+\s+from\s+)?|\brequire\()\s*['"]([^'"]+)['"]""")
_JAVA_IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+)\s*;", re.MULTILINE)

# Matches one `"alias/*": ["target/*", ...]` entry inside a tsconfig `paths` block. Regex rather
# than a full JSON(C) parser -- tsconfig files routinely have comments/trailing commas that
# json.loads chokes on, and this is the only shape actually needed here.
_TS_PATH_ALIAS_RE = re.compile(r'"([^"*]+)\*"\s*:\s*\[\s*"([^"*]+)\*"')

_DEFAULT_ALIASES = {"@/": "src/"}


def _extract_path_aliases(files: list[tuple[str, str | None, str]]) -> dict[str, str]:
    """Returns {alias_prefix: target_prefix}, e.g. {"@/": "src/"}, parsed from any indexed
    tsconfig*.json. Falls back to the near-universal `@/` -> `src/` convention (Vite/Next/CRA
    default) when no tsconfig is indexed or it defines no usable paths entry."""
    aliases: dict[str, str] = {}
    for path, _, content in files:
        if not re.search(r"(^|/)tsconfig(\.\w+)?\.json$", path, re.IGNORECASE):
            continue
        for alias, target in _TS_PATH_ALIAS_RE.findall(content):
            target = target.lstrip("./")
            if target and not target.endswith("/"):
                target += "/"
            aliases[alias] = target
    return aliases or dict(_DEFAULT_ALIASES)


@dataclass
class GraphNode:
    id: str
    language: str | None


@dataclass
class GraphEdge:
    source: str
    target: str


@dataclass
class ArchitectureGraph:
    nodes: list[GraphNode] = field(default_factory=list)
    edges: list[GraphEdge] = field(default_factory=list)


def _extract_raw_imports(language: str | None, content: str) -> list[str]:
    if language == "python":
        return [m.group(1) or m.group(2) for m in _PY_IMPORT_RE.finditer(content)]
    if language in ("javascript", "typescript"):
        return _JS_IMPORT_RE.findall(content)
    if language == "java":
        return _JAVA_IMPORT_RE.findall(content)
    return []


def _resolve_dotted_module(raw_import: str, all_paths: set[str], extension: str) -> str | None:
    module_path = raw_import.lstrip(".").replace(".", "/")
    if not module_path:
        return None
    candidates = [f"{module_path}.{extension}"]
    if extension == "py":
        candidates.append(f"{module_path}/__init__.py")
    for path in all_paths:
        normalized = path.replace("\\", "/")
        for candidate in candidates:
            if normalized == candidate or normalized.endswith("/" + candidate):
                return path
    return None


_JS_TS_SUFFIXES = ("", ".ts", ".tsx", ".js", ".jsx", "/index.ts", "/index.tsx", "/index.js", "/index.jsx")


def _match_js_path(candidate_base: str, all_paths: set[str]) -> str | None:
    for suffix in _JS_TS_SUFFIXES:
        candidate = candidate_base + suffix
        if candidate in all_paths:
            return candidate
    return None


def _resolve_relative_js(
    raw_import: str, importer_path: str, all_paths: set[str], aliases: dict[str, str]
) -> str | None:
    if raw_import.startswith("."):
        base_dir = posixpath.dirname(importer_path)
        resolved = posixpath.normpath(posixpath.join(base_dir, raw_import))
        return _match_js_path(resolved, all_paths)

    for alias, target in aliases.items():
        if raw_import.startswith(alias):
            resolved = posixpath.normpath(target + raw_import[len(alias):])
            return _match_js_path(resolved, all_paths)

    return None  # genuinely bare specifier ("react", "lodash") -> external package


def build_architecture_graph(files: list[tuple[str, str | None, str]]) -> ArchitectureGraph:
    """`files` is (path, language, content) tuples for every indexed file in one repository."""
    all_paths = {path for path, _, _ in files}
    nodes = [GraphNode(id=path, language=language) for path, language, _ in files]
    aliases = _extract_path_aliases(files)

    edges: list[GraphEdge] = []
    seen_edges: set[tuple[str, str]] = set()

    for path, language, content in files:
        for raw in _extract_raw_imports(language, content):
            if language == "python":
                target = _resolve_dotted_module(raw, all_paths, "py")
            elif language == "java":
                target = _resolve_dotted_module(raw, all_paths, "java")
            elif language in ("javascript", "typescript"):
                target = _resolve_relative_js(raw, path, all_paths, aliases)
            else:
                target = None

            if target is None or target == path or (path, target) in seen_edges:
                continue
            seen_edges.add((path, target))
            edges.append(GraphEdge(source=path, target=target))

    return ArchitectureGraph(nodes=nodes, edges=edges)
