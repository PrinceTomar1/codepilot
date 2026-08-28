from app.services.chunking import MAX_CHUNK_LINES, chunk_file


def test_java_splits_by_method_boundaries():
    content = """package com.example;

public class Foo {
    public void bar() {
        System.out.println("bar");
    }

    private int baz(int x) {
        return x + 1;
    }
}
"""
    chunks = chunk_file("src/Foo.java", "java", content)
    assert len(chunks) >= 2
    # Every chunk should preserve its own contiguous line range and content.
    for c in chunks:
        assert c.file_path == "src/Foo.java"
        assert c.language == "java"
        assert c.start_line <= c.end_line
        assert c.content.strip() != ""


def test_javascript_splits_functions_and_arrow_consts():
    content = """import React from 'react';

function Greeter(name) {
  return `Hello ${name}`;
}

const Widget = () => {
  return null;
};

export class Panel {
  render() {
    return true;
  }
}
"""
    chunks = chunk_file("src/App.jsx", "javascript", content)
    assert len(chunks) >= 2
    joined = "\n".join(c.content for c in chunks)
    assert "Greeter" in joined
    assert "Widget" in joined
    assert "Panel" in joined


def test_markdown_splits_by_heading():
    content = """# Title

Intro text.

## Section A

Some content here.

## Section B

More content.
"""
    chunks = chunk_file("README.md", "markdown", content)
    assert len(chunks) == 3
    assert chunks[0].content.startswith("# Title")
    assert chunks[1].content.startswith("## Section A")
    assert chunks[2].content.startswith("## Section B")


def test_fallback_sliding_window_for_unknown_language():
    lines = [f"line {i}" for i in range(1, 201)]
    content = "\n".join(lines)
    chunks = chunk_file("data/notes.txt", "plaintext", content)
    assert len(chunks) > 1
    # First chunk starts at line 1.
    assert chunks[0].start_line == 1
    # Consecutive chunks should overlap (sliding window), so the second
    # chunk's start should be before the first chunk's end.
    assert chunks[1].start_line < chunks[0].end_line
    # Last chunk should reach the end of the file.
    assert chunks[-1].end_line == 200


def test_large_function_is_capped_to_max_chunk_lines():
    body_lines = "\n".join(f"    System.out.println({i});" for i in range(400))
    content = f"public class Big {{\n    public void huge() {{\n{body_lines}\n    }}\n}}\n"
    chunks = chunk_file("src/Big.java", "java", content)
    for c in chunks:
        line_count = c.end_line - c.start_line + 1
        assert line_count <= MAX_CHUNK_LINES


def test_empty_file_produces_no_chunks():
    assert chunk_file("empty.py", "python", "") == []


def test_java_chunks_carry_their_symbol_name():
    content = """package com.example;

public class Foo {
    public void bar() {
        System.out.println("bar");
    }

    private int baz(int x) {
        return x + 1;
    }
}
"""
    chunks = chunk_file("src/Foo.java", "java", content)
    names = {c.symbol_name for c in chunks if c.symbol_name}
    assert "Foo" in names
    assert "bar" in names
    assert "baz" in names


def test_javascript_chunks_carry_their_symbol_name():
    content = """function Greeter(name) {
  return `Hello ${name}`;
}

const Widget = () => {
  return null;
};

export class Panel {
  render() {
    return true;
  }
}
"""
    chunks = chunk_file("src/App.jsx", "javascript", content)
    names = {c.symbol_name for c in chunks if c.symbol_name}
    assert "Greeter" in names
    assert "Widget" in names
    assert "Panel" in names


def test_python_now_gets_structure_aware_chunking_not_just_sliding_window():
    # Real gap found while adding symbol names: Python had no boundary-detection heuristic at
    # all before this -- every .py file always fell through to the generic sliding-window
    # fallback regardless of function/class boundaries, unlike Java/JS/Markdown.
    content = """import requests


def fetch(url):
    return requests.get(url)


class Client:
    def __init__(self):
        self.session = requests.Session()

    def get(self, url):
        return self.session.get(url)
"""
    chunks = chunk_file("client.py", "python", content)
    names = {c.symbol_name for c in chunks if c.symbol_name}
    assert "fetch" in names
    assert "Client" in names
    assert "__init__" in names
    assert "get" in names
    # And it should have actually split by boundary, not returned one big sliding-window chunk.
    assert len(chunks) >= 3


def test_markdown_symbol_name_is_the_heading_text():
    content = "# Title\n\nIntro.\n\n## Section A\n\nBody.\n"
    chunks = chunk_file("README.md", "markdown", content)
    assert chunks[0].symbol_name == "Title"
    assert chunks[1].symbol_name == "Section A"


def test_sliding_window_fallback_chunks_have_no_symbol_name():
    lines = [f"line {i}" for i in range(1, 201)]
    content = "\n".join(lines)
    chunks = chunk_file("data/notes.txt", "plaintext", content)
    assert all(c.symbol_name is None for c in chunks)


def test_oversized_function_only_keeps_symbol_name_on_the_first_piece():
    body_lines = "\n".join(f"    System.out.println({i});" for i in range(400))
    content = f"public class Big {{\n    public void huge() {{\n{body_lines}\n    }}\n}}\n"
    chunks = chunk_file("src/Big.java", "java", content)
    named = [c for c in chunks if c.symbol_name == "huge"]
    assert len(named) == 1
