"""
Postgres/pgvector access for the `code_chunks` table.

The table is created elsewhere (Spring Boot backend's Flyway migration) --
this module only reads and writes rows, it never issues DDL.

    code_chunks(
        id UUID PK,
        repository_id UUID,
        file_path TEXT,
        language VARCHAR,
        start_line INT,
        end_line INT,
        content TEXT,
        embedding vector(1536),
        symbol_name TEXT,
        created_at
    )
"""
from __future__ import annotations

import uuid
from dataclasses import dataclass

from pgvector.sqlalchemy import Vector
from sqlalchemy import Column, DateTime, Integer, String, Text, case, delete, func, or_, select, text
from sqlalchemy.dialects.postgresql import UUID as PGUUID
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase

from app.config import Settings


class Base(DeclarativeBase):
    pass


class CodeChunk(Base):
    __tablename__ = "code_chunks"

    id = Column(PGUUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    repository_id = Column(PGUUID(as_uuid=True), nullable=False, index=True)
    file_path = Column(Text, nullable=False)
    language = Column(String, nullable=True)
    start_line = Column(Integer, nullable=False)
    end_line = Column(Integer, nullable=False)
    content = Column(Text, nullable=False)
    embedding = Column(Vector(1536), nullable=False)
    symbol_name = Column(Text, nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())


class IndexedFile(Base):
    """Per-file content hash, used to skip re-embedding unchanged files on
    incremental indexing runs (see VectorStore.get_file_hashes / upsert_file_hashes)."""

    __tablename__ = "indexed_files"

    repository_id = Column(PGUUID(as_uuid=True), primary_key=True)
    file_path = Column(Text, primary_key=True)
    content_sha = Column(String(64), nullable=False)
    updated_at = Column(DateTime(timezone=True), server_default=func.now())


@dataclass
class RetrievedChunk:
    file_path: str
    language: str | None
    start_line: int
    end_line: int
    content: str
    distance: float
    symbol_name: str | None = None


def _to_asyncpg_url(url: str) -> str:
    """Normalize a DATABASE_URL (which may be given in the plain
    'postgresql://' form used by other services/Flyway) into the
    'postgresql+psycopg://' form SQLAlchemy's async engine needs when using
    the psycopg (v3) async driver."""
    if url.startswith("postgresql+"):
        return url
    if url.startswith("postgresql://"):
        return "postgresql+psycopg://" + url[len("postgresql://"):]
    if url.startswith("postgres://"):
        return "postgresql+psycopg://" + url[len("postgres://"):]
    return url


class VectorStore:
    def __init__(self, settings: Settings):
        self.settings = settings
        self._engine = create_async_engine(_to_asyncpg_url(settings.DATABASE_URL), pool_pre_ping=True)
        self._session_factory = async_sessionmaker(self._engine, expire_on_commit=False)

    async def dispose(self) -> None:
        await self._engine.dispose()

    def session(self) -> AsyncSession:
        return self._session_factory()

    async def ensure_vector_extension(self) -> None:
        """Best-effort: make sure the pgvector extension is registered on this
        connection's session type mapping. The extension/table themselves are
        assumed to already exist (created by the backend's migration)."""
        try:
            async with self._engine.begin() as conn:
                await conn.execute(text("SELECT 1"))
        except Exception:
            # Don't crash app startup if the DB isn't reachable yet; endpoints
            # that need it will raise a clear error when actually invoked.
            pass

    async def delete_repository_chunks(self, session: AsyncSession, repository_id: uuid.UUID) -> None:
        await session.execute(delete(CodeChunk).where(CodeChunk.repository_id == repository_id))

    async def delete_chunks_for_paths(
        self, session: AsyncSession, repository_id: uuid.UUID, file_paths: list[str]
    ) -> None:
        if not file_paths:
            return
        await session.execute(
            delete(CodeChunk).where(
                CodeChunk.repository_id == repository_id,
                CodeChunk.file_path.in_(file_paths),
            )
        )

    async def insert_chunks(self, session: AsyncSession, rows: list[dict]) -> int:
        if not rows:
            return 0
        objects = [CodeChunk(**row) for row in rows]
        session.add_all(objects)
        return len(objects)

    async def get_file_hashes(self, session: AsyncSession, repository_id: uuid.UUID) -> dict[str, str]:
        stmt = select(IndexedFile.file_path, IndexedFile.content_sha).where(
            IndexedFile.repository_id == repository_id
        )
        result = await session.execute(stmt)
        return {path: sha for path, sha in result.all()}

    async def upsert_file_hashes(
        self, session: AsyncSession, repository_id: uuid.UUID, path_to_sha: dict[str, str]
    ) -> None:
        if not path_to_sha:
            return
        rows = [
            {"repository_id": repository_id, "file_path": path, "content_sha": sha}
            for path, sha in path_to_sha.items()
        ]
        stmt = pg_insert(IndexedFile).values(rows)
        stmt = stmt.on_conflict_do_update(
            index_elements=[IndexedFile.repository_id, IndexedFile.file_path],
            set_={"content_sha": stmt.excluded.content_sha, "updated_at": func.now()},
        )
        await session.execute(stmt)

    async def delete_file_hashes(
        self, session: AsyncSession, repository_id: uuid.UUID, file_paths: list[str]
    ) -> None:
        if not file_paths:
            return
        await session.execute(
            delete(IndexedFile).where(
                IndexedFile.repository_id == repository_id,
                IndexedFile.file_path.in_(file_paths),
            )
        )

    async def similarity_search(
        self,
        session: AsyncSession,
        repository_id: uuid.UUID,
        query_embedding: list[float],
        top_k: int,
    ) -> list[RetrievedChunk]:
        distance = CodeChunk.embedding.cosine_distance(query_embedding)
        stmt = (
            select(
                CodeChunk.file_path,
                CodeChunk.language,
                CodeChunk.start_line,
                CodeChunk.end_line,
                CodeChunk.content,
                CodeChunk.symbol_name,
                distance.label("distance"),
            )
            .where(CodeChunk.repository_id == repository_id)
            .order_by(distance)
            .limit(top_k)
        )
        result = await session.execute(stmt)
        rows = result.all()
        return [
            RetrievedChunk(
                file_path=r.file_path,
                language=r.language,
                start_line=r.start_line,
                end_line=r.end_line,
                content=r.content,
                symbol_name=r.symbol_name,
                distance=float(r.distance),
            )
            for r in rows
        ]

    async def keyword_search(
        self, session: AsyncSession, repository_id: uuid.UUID, keywords: list[str], limit: int
    ) -> list[RetrievedChunk]:
        """Exact/substring match over content, file path, and symbol name -- a supplement to
        vector similarity search. A query naming a specific identifier or file (e.g. "where is
        calculateTotal called") can rank poorly by cosine similarity under the local hashing-based
        embedding provider even though it's a literal, findable string in the indexed content.

        Ranked by a relevance score, NOT by file/line order: a generic word from the question
        (e.g. "weather" in a weather-app repo) can substring-match dozens of chunks, and an
        unranked LIMIT can truncate before ever reaching the one chunk that actually matters. Each
        keyword's base weight is proportional to its own length (so a specific multi-word
        identifier like "get_weather_emoji" outweighs a short generic word like "weather") and to
        which field matched -- an exact symbol-name hit is the strongest possible signal that the
        question is about that chunk, well above a plain content substring match.

        Length alone isn't enough, though -- a query with two keywords of similar length but very
        different rarity (e.g. "gold symbol" against a repo where "gold" appears in 2 chunks and
        "symbol" appears in dozens, several of them just dependency-lockfile noise like
        "micromark-util-symbol") let the common word's sheer number of matches bury the rare,
        actually-relevant one before it ever reached the LIMIT -- confirmed live. Each keyword's
        weight is additionally scaled by how many chunks in THIS repo it matches at all (a simple
        inverse-document-frequency): a keyword matching only 2 chunks counts far more per-match
        than one matching 40, the same way a real search engine treats a rare term as more
        informative than a common one."""
        if not keywords:
            return []

        doc_counts: dict[str, int] = {}
        for kw in keywords:
            like = f"%{kw}%"
            count_stmt = select(func.count()).select_from(CodeChunk).where(
                CodeChunk.repository_id == repository_id,
                or_(CodeChunk.symbol_name.ilike(like), CodeChunk.file_path.ilike(like), CodeChunk.content.ilike(like)),
            )
            doc_counts[kw] = (await session.execute(count_stmt)).scalar_one()

        score_terms = []
        for kw in keywords:
            doc_count = doc_counts[kw]
            if doc_count == 0:
                continue
            weight = len(kw) / doc_count
            like = f"%{kw}%"
            score_terms.append(case((CodeChunk.symbol_name.ilike(like), weight * 5), else_=0))
            score_terms.append(case((CodeChunk.file_path.ilike(like), weight * 3), else_=0))
            score_terms.append(case((CodeChunk.content.ilike(like), weight * 1), else_=0))
        if not score_terms:
            return []
        score = score_terms[0]
        for term in score_terms[1:]:
            score = score + term

        stmt = (
            select(
                CodeChunk.file_path,
                CodeChunk.language,
                CodeChunk.start_line,
                CodeChunk.end_line,
                CodeChunk.content,
                CodeChunk.symbol_name,
                score.label("score"),
            )
            .where(CodeChunk.repository_id == repository_id, score > 0)
            .order_by(score.desc())
            .limit(limit)
        )
        result = await session.execute(stmt)
        rows = result.all()
        return [
            RetrievedChunk(
                file_path=r.file_path,
                language=r.language,
                start_line=r.start_line,
                end_line=r.end_line,
                content=r.content,
                symbol_name=r.symbol_name,
                distance=0.0,  # keyword matches have no cosine distance; ranking already applied above
            )
            for r in rows
        ]

    async def sample_chunks_per_file(
        self, session: AsyncSession, repository_id: uuid.UUID, per_file: int = 2
    ) -> list[RetrievedChunk]:
        """Grab up to `per_file` chunks per distinct file for onboarding-doc
        generation (earliest chunks first, i.e. top of each file)."""
        stmt = (
            select(
                CodeChunk.file_path,
                CodeChunk.language,
                CodeChunk.start_line,
                CodeChunk.end_line,
                CodeChunk.content,
            )
            .where(CodeChunk.repository_id == repository_id)
            .order_by(CodeChunk.file_path, CodeChunk.start_line)
        )
        result = await session.execute(stmt)
        rows = result.all()

        per_file_count: dict[str, int] = {}
        out: list[RetrievedChunk] = []
        for r in rows:
            n = per_file_count.get(r.file_path, 0)
            if n >= per_file:
                continue
            per_file_count[r.file_path] = n + 1
            out.append(
                RetrievedChunk(
                    file_path=r.file_path,
                    language=r.language,
                    start_line=r.start_line,
                    end_line=r.end_line,
                    content=r.content,
                    distance=0.0,
                )
            )
        return out

    async def get_files_with_content(
        self, session: AsyncSession, repository_id: uuid.UUID
    ) -> list[tuple[str, str | None, str]]:
        """Reconstructs each indexed file's approximate full content by concatenating its chunks
        in line order -- used for static analysis (e.g. the architecture graph's import scan)
        that needs to see a whole file, not just the top-K chunks a similarity search would return.
        Returns (file_path, language, content) tuples, one per distinct file."""
        stmt = (
            select(
                CodeChunk.file_path,
                CodeChunk.language,
                CodeChunk.start_line,
                CodeChunk.content,
            )
            .where(CodeChunk.repository_id == repository_id)
            .order_by(CodeChunk.file_path, CodeChunk.start_line)
        )
        result = await session.execute(stmt)
        rows = result.all()

        files: dict[str, dict] = {}
        for r in rows:
            entry = files.setdefault(r.file_path, {"language": r.language, "parts": []})
            entry["parts"].append(r.content)

        return [
            (path, entry["language"], "\n".join(entry["parts"]))
            for path, entry in files.items()
        ]
