"""
build_architecture_graph must produce a genuinely different, computed result per repository --
these tests exercise real import syntax for each supported language and check the resolved edges,
not just that *some* graph comes back.
"""
from __future__ import annotations

from app.services.architecture import GraphEdge, build_architecture_graph


def test_python_absolute_import_resolves_by_suffix():
    files = [
        ("app/services/llm.py", "python", "class LLMClient:\n    pass\n"),
        ("app/services/rag.py", "python", "from app.services.llm import LLMClient\n"),
    ]
    graph = build_architecture_graph(files)

    node_ids = {n.id for n in graph.nodes}
    assert node_ids == {"app/services/llm.py", "app/services/rag.py"}
    assert len(graph.edges) == 1
    assert graph.edges[0].source == "app/services/rag.py"
    assert graph.edges[0].target == "app/services/llm.py"


def test_python_package_import_resolves_to_init():
    files = [
        ("app/agents/__init__.py", "python", ""),
        ("app/main.py", "python", "import app.agents\n"),
    ]
    graph = build_architecture_graph(files)

    assert graph.edges == [GraphEdge(source="app/main.py", target="app/agents/__init__.py")]


def test_java_import_resolves_despite_src_main_java_prefix():
    files = [
        ("src/main/java/com/codepilot/service/AuthService.java", "java",
         "package com.codepilot.service;\nclass AuthService {}\n"),
        ("src/main/java/com/codepilot/controller/AuthController.java", "java",
         "package com.codepilot.controller;\nimport com.codepilot.service.AuthService;\nclass AuthController {}\n"),
    ]
    graph = build_architecture_graph(files)

    assert len(graph.edges) == 1
    assert graph.edges[0].source == "src/main/java/com/codepilot/controller/AuthController.java"
    assert graph.edges[0].target == "src/main/java/com/codepilot/service/AuthService.java"


def test_typescript_relative_import_resolves_against_importer_directory():
    files = [
        ("src/api/client.ts", "typescript", "export const apiClient = {}\n"),
        ("src/api/auth.ts", "typescript", "import { apiClient } from './client'\n"),
        ("src/pages/LoginPage.tsx", "typescript", "import { login } from '../api/auth'\n"),
    ]
    graph = build_architecture_graph(files)

    edge_pairs = {(e.source, e.target) for e in graph.edges}
    assert ("src/api/auth.ts", "src/api/client.ts") in edge_pairs
    assert ("src/pages/LoginPage.tsx", "src/api/auth.ts") in edge_pairs


def test_bare_specifier_is_treated_as_external_not_an_edge():
    files = [
        ("src/App.tsx", "typescript", "import { useState } from 'react'\nimport axios from 'axios'\n"),
    ]
    graph = build_architecture_graph(files)

    assert graph.edges == []
    assert len(graph.nodes) == 1


def test_no_imports_produces_disconnected_nodes_and_zero_edges():
    files = [
        ("README.md", None, "# hello\n"),
        ("standalone.py", "python", "x = 1\n"),
    ]
    graph = build_architecture_graph(files)

    assert len(graph.nodes) == 2
    assert graph.edges == []


def test_self_import_is_excluded():
    # Shouldn't happen in real code, but a resolver bug that lets a file "depend on itself"
    # would be a visibly wrong graph -- guard against it explicitly.
    files = [
        ("app/x.py", "python", "import app.x\n"),
    ]
    graph = build_architecture_graph(files)

    assert graph.edges == []


def test_duplicate_imports_produce_a_single_edge():
    files = [
        ("app/b.py", "python", ""),
        ("app/a.py", "python", "import app.b\nimport app.b\nfrom app.b import something\n"),
    ]
    graph = build_architecture_graph(files)

    assert len(graph.edges) == 1


def test_at_slash_path_alias_resolves_via_default_convention_without_tsconfig():
    # Real bug: an 87-file repo produced only 13 edges because every "@/..." import
    # (the standard Vite/Next/CRA alias for src/) was silently treated as external and dropped.
    files = [
        ("src/components/ui/button.tsx", "typescript", "export const Button = () => null\n"),
        ("src/App.tsx", "typescript", "import { Button } from '@/components/ui/button'\n"),
    ]
    graph = build_architecture_graph(files)

    assert graph.edges == [GraphEdge(source="src/App.tsx", target="src/components/ui/button.tsx")]


def test_path_alias_resolves_via_actual_tsconfig_mapping_when_present():
    # A repo whose alias doesn't point at src/ (e.g. a monorepo aliasing into shared/) must be
    # resolved using its own tsconfig, not the hardcoded default.
    files = [
        ("tsconfig.json", None, '{"compilerOptions": {"paths": {"@/*": ["./shared/*"]}}}'),
        ("shared/utils/format.ts", "typescript", "export function format() {}\n"),
        ("app/main.ts", "typescript", "import { format } from '@/utils/format'\n"),
    ]
    graph = build_architecture_graph(files)

    assert graph.edges == [GraphEdge(source="app/main.ts", target="shared/utils/format.ts")]


def test_custom_alias_name_from_tsconfig_is_honored_not_just_at_slash():
    files = [
        ("tsconfig.json", None, '{"compilerOptions": {"paths": {"~/*": ["./src/*"]}}}'),
        ("src/lib/api.ts", "typescript", "export const api = {}\n"),
        ("src/App.tsx", "typescript", "import { api } from '~/lib/api'\n"),
    ]
    graph = build_architecture_graph(files)

    assert graph.edges == [GraphEdge(source="src/App.tsx", target="src/lib/api.ts")]


def test_scoped_npm_package_is_not_confused_with_a_path_alias():
    # "@tanstack/react-query" and "@vercel/analytics/react" look superficially similar to "@/foo"
    # but are genuine external scoped npm packages (no slash immediately after "@") -- must not
    # be resolved as if "@" alone were the alias prefix.
    files = [
        ("src/App.tsx", "typescript",
         "import { useQuery } from '@tanstack/react-query'\nimport { Analytics } from '@vercel/analytics/react'\n"),
    ]
    graph = build_architecture_graph(files)

    assert graph.edges == []
    assert len(graph.nodes) == 1
