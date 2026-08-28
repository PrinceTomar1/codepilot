from __future__ import annotations

import hashlib
import logging
import uuid

from fastapi import APIRouter, Depends, HTTPException

from app.deps import get_embedding_provider, get_vector_store
from app.models.schemas import IndexRequest, IndexResponse
from app.services.chunking import chunk_file
from app.services.embeddings import EmbeddingProvider
from app.services.vector_store import VectorStore

logger = logging.getLogger("codepilot.index")

router = APIRouter()

EMBED_BATCH_SIZE = 64


def _content_sha(content: str) -> str:
    return hashlib.sha256(content.encode("utf-8")).hexdigest()


@router.post("/index", response_model=IndexResponse)
async def index_repository(
    body: IndexRequest,
    store: VectorStore = Depends(get_vector_store),
    embedder: EmbeddingProvider = Depends(get_embedding_provider),
) -> IndexResponse:
    try:
        repository_id = uuid.UUID(body.repository_id)
    except ValueError:
        raise HTTPException(status_code=400, detail=f"repositoryId is not a valid UUID: {body.repository_id!r}")

    incoming_hashes = {f.path: _content_sha(f.content) for f in body.files}

    # 1. Diff against previously-indexed file hashes: only (re-)chunk and
    #    (re-)embed files that are new or whose content actually changed.
    #    Files present before but missing now are treated as deleted.
    try:
        async with store.session() as session:
            existing_hashes = await store.get_file_hashes(session, repository_id)
    except Exception as exc:
        logger.exception("Database error while loading existing file hashes")
        raise HTTPException(status_code=502, detail=f"Database error: {exc}")

    changed_files = [f for f in body.files if existing_hashes.get(f.path) != incoming_hashes[f.path]]
    removed_paths = [path for path in existing_hashes if path not in incoming_hashes]
    unchanged_count = len(body.files) - len(changed_files)

    # 2. Chunk only the changed/new files.
    all_chunks = []
    for f in changed_files:
        all_chunks.extend(chunk_file(f.path, f.language, f.content))

    # 3. Embed every new chunk (batched).
    embeddings: list[list[float]] = []
    if all_chunks:
        texts = [c.content for c in all_chunks]
        try:
            for i in range(0, len(texts), EMBED_BATCH_SIZE):
                batch = texts[i:i + EMBED_BATCH_SIZE]
                embeddings.extend(await embedder.embed(batch))
        except Exception as exc:
            logger.exception("Embedding failed during /index")
            raise HTTPException(status_code=502, detail=f"Embedding provider error: {exc}")

    # 4. Apply the diff: drop chunks/hashes for changed+removed files, insert
    #    fresh chunks for changed files, record their new hashes. Unchanged
    #    files are untouched entirely -- no delete, no re-embed.
    changed_paths = [f.path for f in changed_files]
    try:
        async with store.session() as session:
            async with session.begin():
                paths_to_clear = changed_paths + removed_paths
                await store.delete_chunks_for_paths(session, repository_id, paths_to_clear)
                await store.delete_file_hashes(session, repository_id, removed_paths)

                rows = [
                    {
                        "id": uuid.uuid4(),
                        "repository_id": repository_id,
                        "file_path": chunk.file_path,
                        "language": chunk.language,
                        "start_line": chunk.start_line,
                        "end_line": chunk.end_line,
                        "content": chunk.content,
                        "embedding": embedding,
                        "symbol_name": chunk.symbol_name,
                    }
                    for chunk, embedding in zip(all_chunks, embeddings)
                ]
                await store.insert_chunks(session, rows)

                new_hashes = {f.path: incoming_hashes[f.path] for f in changed_files}
                await store.upsert_file_hashes(session, repository_id, new_hashes)
    except HTTPException:
        raise
    except Exception as exc:
        logger.exception("Database error during /index")
        raise HTTPException(status_code=502, detail=f"Database error: {exc}")

    logger.info(
        "Indexed repository %s: %d changed, %d unchanged (skipped), %d removed, %d chunks created",
        repository_id, len(changed_files), unchanged_count, len(removed_paths), len(all_chunks),
    )

    return IndexResponse(
        repositoryId=body.repository_id,
        filesIndexed=len(body.files),
        chunksCreated=len(all_chunks),
        status="COMPLETED",
    )
