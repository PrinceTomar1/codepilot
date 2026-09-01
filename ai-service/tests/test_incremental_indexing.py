"""
Integration test for the file-hash diffing that backs incremental indexing
(VectorStore.get_file_hashes / upsert_file_hashes / delete_chunks_for_paths).

Runs against a real Postgres + pgvector instance (DATABASE_URL), since the
diff logic is expressed as SQL upserts/deletes that are not meaningful to
fake with a mock. A throwaway user + repository row is created and torn down
per test so it never depends on (or pollutes) real application data.
"""
from __future__ import annotations

import uuid

import pytest
from sqlalchemy import text

from app.config import get_settings
from app.services.vector_store import VectorStore


@pytest.fixture
async def repo_id():
    settings = get_settings()
    store = VectorStore(settings)
    user_id = uuid.uuid4()
    repository_id = uuid.uuid4()

    async with store.session() as session:
        async with session.begin():
            await session.execute(
                text(
                    "INSERT INTO users (id, email, password_hash, name, email_verified) "
                    "VALUES (:id, :email, 'x', 'Test User', true)"
                ),
                {"id": user_id, "email": f"{user_id}@test.local"},
            )
            await session.execute(
                text(
                    "INSERT INTO code_repositories (id, user_id, github_owner, github_repo) "
                    "VALUES (:id, :user_id, 'octocat', 'hello-world')"
                ),
                {"id": repository_id, "user_id": user_id},
            )

    yield store, repository_id

    async with store.session() as session:
        async with session.begin():
            await session.execute(text("DELETE FROM users WHERE id = :id"), {"id": user_id})
    await store.dispose()


async def test_upsert_and_read_file_hashes(repo_id):
    store, repository_id = repo_id

    async with store.session() as session:
        async with session.begin():
            hashes = await store.get_file_hashes(session, repository_id)
            assert hashes == {}

    async with store.session() as session:
        async with session.begin():
            await store.upsert_file_hashes(
                session, repository_id, {"src/a.py": "hash-a", "src/b.py": "hash-b"}
            )

    async with store.session() as session:
        async with session.begin():
            hashes = await store.get_file_hashes(session, repository_id)
            assert hashes == {"src/a.py": "hash-a", "src/b.py": "hash-b"}


async def test_upsert_overwrites_existing_hash(repo_id):
    store, repository_id = repo_id

    async with store.session() as session:
        async with session.begin():
            await store.upsert_file_hashes(session, repository_id, {"src/a.py": "hash-1"})

    async with store.session() as session:
        async with session.begin():
            await store.upsert_file_hashes(session, repository_id, {"src/a.py": "hash-2"})

    async with store.session() as session:
        async with session.begin():
            hashes = await store.get_file_hashes(session, repository_id)
            assert hashes == {"src/a.py": "hash-2"}


async def test_delete_chunks_for_paths_only_removes_matching_files(repo_id):
    store, repository_id = repo_id

    async with store.session() as session:
        async with session.begin():
            await store.insert_chunks(
                session,
                [
                    {
                        "id": uuid.uuid4(),
                        "repository_id": repository_id,
                        "file_path": path,
                        "language": "python",
                        "start_line": 1,
                        "end_line": 5,
                        "content": f"content for {path}",
                        "embedding": [0.0] * 1536,
                    }
                    for path in ("src/keep.py", "src/remove.py")
                ],
            )

    async with store.session() as session:
        async with session.begin():
            await store.delete_chunks_for_paths(session, repository_id, ["src/remove.py"])

    async with store.session() as session:
        async with session.begin():
            remaining = await store.sample_chunks_per_file(session, repository_id, per_file=5)
            paths = {c.file_path for c in remaining}
            assert paths == {"src/keep.py"}


async def test_delete_file_hashes_removes_only_given_paths(repo_id):
    store, repository_id = repo_id

    async with store.session() as session:
        async with session.begin():
            await store.upsert_file_hashes(
                session, repository_id, {"src/a.py": "h1", "src/b.py": "h2"}
            )

    async with store.session() as session:
        async with session.begin():
            await store.delete_file_hashes(session, repository_id, ["src/a.py"])

    async with store.session() as session:
        async with session.begin():
            hashes = await store.get_file_hashes(session, repository_id)
            assert hashes == {"src/b.py": "h2"}


async def test_keyword_search_matches_content_file_path_and_symbol_name(repo_id):
    store, repository_id = repo_id

    async with store.session() as session:
        async with session.begin():
            await store.insert_chunks(
                session,
                [
                    {
                        "id": uuid.uuid4(),
                        "repository_id": repository_id,
                        "file_path": "src/weather_app.py",
                        "language": "python",
                        "start_line": 1,
                        "end_line": 5,
                        "content": "def fetch_weather_data(city): return requests.get(city)",
                        "embedding": [0.0] * 1536,
                        "symbol_name": "fetch_weather_data",
                    },
                    {
                        "id": uuid.uuid4(),
                        "repository_id": repository_id,
                        "file_path": "src/unrelated.py",
                        "language": "python",
                        "start_line": 1,
                        "end_line": 5,
                        "content": "def something_else(): pass",
                        "embedding": [0.0] * 1536,
                        "symbol_name": "something_else",
                    },
                ],
            )

    async with store.session() as session:
        async with session.begin():
            by_symbol = await store.keyword_search(session, repository_id, ["fetch_weather_data"], limit=10)
            assert {c.file_path for c in by_symbol} == {"src/weather_app.py"}

            by_filename = await store.keyword_search(session, repository_id, ["weather_app.py"], limit=10)
            assert {c.file_path for c in by_filename} == {"src/weather_app.py"}

            no_match = await store.keyword_search(session, repository_id, ["nonexistent_xyz"], limit=10)
            assert no_match == []

            empty_keywords = await store.keyword_search(session, repository_id, [], limit=10)
            assert empty_keywords == []


async def test_keyword_search_ranks_a_rare_keyword_above_a_common_one_of_similar_length(repo_id):
    # Real bug: asking "gold symbol" against a real repository never surfaced the
    # two chunks that actually mention "gold", because "symbol" (only 2 letters longer) happened
    # to substring-match dozens of unrelated chunks -- several of them just dependency-lockfile
    # noise -- and the old scoring (weight = keyword length only) let that sheer volume of common,
    # low-relevance matches outrank and bury the rare, actually-relevant ones before the query's
    # own LIMIT was ever reached. Weight now also scales inversely with how many chunks in this
    # repo the keyword matches at all, so a keyword matching almost everything counts for much
    # less per-match than one matching almost nothing.
    store, repository_id = repo_id

    async with store.session() as session:
        async with session.begin():
            await store.insert_chunks(
                session,
                [
                    {
                        "id": uuid.uuid4(),
                        "repository_id": repository_id,
                        "file_path": "content/nft-ip.mdx",
                        "language": "text",
                        "start_line": 1,
                        "end_line": 5,
                        "content": "Winning submissions receive a gold badge on their profile.",
                        "embedding": [0.0] * 1536,
                    },
                    *[
                        {
                            "id": uuid.uuid4(),
                            "repository_id": repository_id,
                            "file_path": f"pnpm-lock.yaml#{i}",
                            "language": "yaml",
                            "start_line": 1,
                            "end_line": 5,
                            "content": f"micromark-util-symbol@2.0.{i}: resolution: ...",
                            "embedding": [0.0] * 1536,
                        }
                        for i in range(5)
                    ],
                ],
            )

    async with store.session() as session:
        async with session.begin():
            results = await store.keyword_search(session, repository_id, ["gold", "symbol"], limit=3)
            paths = [c.file_path for c in results]
            assert "content/nft-ip.mdx" in paths, (
                f"the rare, relevant 'gold' chunk was crowded out of the top 3 by common "
                f"'symbol' noise: {paths}"
            )
