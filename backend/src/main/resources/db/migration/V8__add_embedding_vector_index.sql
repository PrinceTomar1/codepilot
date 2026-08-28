-- Every similarity search (VectorStore.similarity_search, cosine_distance) was doing a full
-- sequential scan over ALL rows in code_chunks, computing cosine distance against every one --
-- fine at today's scale (~1-2k chunks total) but a real bottleneck once repositories/chunk
-- counts grow, since it's O(n) per query with no way to short-circuit. HNSW is pgvector's
-- recommended index type for approximate nearest-neighbor search (better recall/build-time
-- tradeoff than ivfflat, and needs no upfront list-count tuning); vector_cosine_ops matches the
-- cosine_distance() operator actually used by the query.
CREATE INDEX idx_code_chunks_embedding_hnsw ON code_chunks
    USING hnsw (embedding vector_cosine_ops);
