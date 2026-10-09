-- Orchard accounts. Timestamps are Unix seconds.

CREATE TABLE users (
    id TEXT PRIMARY KEY,
    email TEXT NOT NULL DEFAULT '',
    name TEXT NOT NULL DEFAULT '',
    picture TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- One row per external login. Linking Discord later is just another provider.
CREATE TABLE identities (
    provider TEXT NOT NULL,
    subject TEXT NOT NULL,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    email TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL,
    PRIMARY KEY (provider, subject)
);
CREATE INDEX identities_user ON identities(user_id);

-- A device is a signed-in install. Deleting the row revokes its refresh token.
CREATE TABLE devices (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    platform TEXT NOT NULL,
    refresh_hash TEXT NOT NULL UNIQUE,
    prev_refresh_hash TEXT,
    rotated_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL
);
CREATE INDEX devices_user ON devices(user_id);

-- Sign-in attempts waiting on Google. Keyed by the state sent to Google.
CREATE TABLE auth_requests (
    id TEXT PRIMARY KEY,
    google_verifier TEXT NOT NULL,
    redirect_uri TEXT NOT NULL,
    client_state TEXT NOT NULL,
    code_challenge TEXT NOT NULL,
    expires_at INTEGER NOT NULL
);

-- One-time codes handed to the app's loopback redirect.
CREATE TABLE auth_codes (
    code_hash TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    redirect_uri TEXT NOT NULL,
    code_challenge TEXT NOT NULL,
    expires_at INTEGER NOT NULL
);
