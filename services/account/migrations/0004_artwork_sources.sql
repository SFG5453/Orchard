-- Source URL digests let another device reuse a hosted conversion before FFmpeg wakes up.
-- Rows from the R2 era stay visible in D1 but cannot advertise missing B2 files.
-- The existing expires_at column is kept for migration compatibility; B2 rows
-- use zero to mean permanent.
ALTER TABLE artwork ADD COLUMN storage TEXT NOT NULL DEFAULT 'r2';
CREATE TABLE artwork_sources (
    user_id TEXT NOT NULL,
    source_sha256 TEXT NOT NULL,
    hash TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, source_sha256)
);
CREATE INDEX artwork_sources_hash ON artwork_sources(hash);
