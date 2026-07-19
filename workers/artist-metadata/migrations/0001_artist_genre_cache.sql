CREATE TABLE IF NOT EXISTS artist_genre_cache (
  cache_key TEXT PRIMARY KEY,
  youtube_browse_id TEXT NOT NULL DEFAULT '',
  requested_artist TEXT NOT NULL,
  requested_album TEXT NOT NULL,
  country TEXT NOT NULL DEFAULT 'US',
  matched INTEGER NOT NULL DEFAULT 0 CHECK (matched IN (0, 1)),
  reason TEXT NOT NULL DEFAULT '',
  genre TEXT NOT NULL DEFAULT '',
  primary_genre_id INTEGER,
  provider_artist_id TEXT NOT NULL DEFAULT '',
  matched_artist_name TEXT NOT NULL DEFAULT '',
  artist_link_url TEXT NOT NULL DEFAULT '',
  confirmed_album TEXT NOT NULL DEFAULT '',
  confidence REAL NOT NULL DEFAULT 0,
  fetched_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS artist_genre_cache_youtube_id
  ON artist_genre_cache (youtube_browse_id);

CREATE INDEX IF NOT EXISTS artist_genre_cache_expiry
  ON artist_genre_cache (expires_at);
