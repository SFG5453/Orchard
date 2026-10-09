-- Temporary artwork for Discord Rich Presence. Objects live in R2 under
-- artwork/<hash>.webp; these rows track expiry and upload quotas.

CREATE TABLE artwork (
    hash TEXT PRIMARY KEY,
    bytes INTEGER NOT NULL,
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    frames INTEGER NOT NULL,
    uploaded_by TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);
CREATE INDEX artwork_expiry ON artwork(expires_at);

-- One row per upload attempt. finished_at IS NULL means in flight.
CREATE TABLE artwork_uploads (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    ip TEXT NOT NULL,
    started_at INTEGER NOT NULL,
    finished_at INTEGER
);
CREATE INDEX artwork_uploads_user ON artwork_uploads(user_id, started_at);
CREATE INDEX artwork_uploads_ip ON artwork_uploads(ip, started_at);
