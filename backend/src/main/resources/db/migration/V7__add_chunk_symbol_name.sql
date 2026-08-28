-- Surfaces the symbol (class/function/method name) that chunking.py already detects at each
-- chunk boundary but previously discarded. Enables exact/keyword symbol search ("where is
-- calculateTotal() defined") as a supplement to vector similarity search, which can rank an
-- exact-name match poorly under the local hashing-based embedding provider.
ALTER TABLE code_chunks
    ADD COLUMN symbol_name TEXT;

CREATE INDEX idx_code_chunks_symbol_name ON code_chunks (repository_id, symbol_name)
    WHERE symbol_name IS NOT NULL;
