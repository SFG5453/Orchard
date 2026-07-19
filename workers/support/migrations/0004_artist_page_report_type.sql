PRAGMA foreign_keys = OFF;

DROP INDEX IF EXISTS idx_reports_client_updated;
DROP INDEX IF EXISTS idx_reports_thread;
DROP INDEX IF EXISTS idx_reports_dispatch;
DROP INDEX IF EXISTS idx_reports_retention;
DROP INDEX IF EXISTS idx_reports_external_issue;
DROP INDEX IF EXISTS idx_reports_external_issue_dispatch;

CREATE TABLE reports_new (
  id TEXT PRIMARY KEY,
  client_id TEXT NOT NULL,
  type TEXT NOT NULL CHECK(type IN ('bug', 'feature', 'feedback', 'artist_page')),
  title TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'open' CHECK(status IN (
    'open', 'waiting_on_user', 'fixed', 'resolved', 'duplicate',
    'unable_to_reproduce', 'declined', 'closed'
  )),
  target_version TEXT NOT NULL DEFAULT '',
  diagnostics_json TEXT,
  discord_thread_id TEXT UNIQUE,
  dispatch_status TEXT NOT NULL DEFAULT 'pending' CHECK(dispatch_status IN ('pending', 'sent', 'failed')),
  user_read_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  closed_at TEXT,
  external_issue_number INTEGER,
  external_issue_url TEXT,
  external_issue_dispatch_status TEXT NOT NULL DEFAULT 'pending'
    CHECK(external_issue_dispatch_status IN ('pending', 'sent', 'failed')),
  FOREIGN KEY(client_id) REFERENCES clients(id) ON DELETE CASCADE
);

INSERT INTO reports_new (
  id,
  client_id,
  type,
  title,
  status,
  target_version,
  diagnostics_json,
  discord_thread_id,
  dispatch_status,
  user_read_at,
  created_at,
  updated_at,
  closed_at,
  external_issue_number,
  external_issue_url,
  external_issue_dispatch_status
)
SELECT
  id,
  client_id,
  type,
  title,
  status,
  target_version,
  diagnostics_json,
  discord_thread_id,
  dispatch_status,
  user_read_at,
  created_at,
  updated_at,
  closed_at,
  external_issue_number,
  external_issue_url,
  external_issue_dispatch_status
FROM reports;

DROP TABLE reports;
ALTER TABLE reports_new RENAME TO reports;

CREATE INDEX idx_reports_client_updated ON reports(client_id, updated_at DESC);
CREATE INDEX idx_reports_thread ON reports(discord_thread_id) WHERE discord_thread_id IS NOT NULL;
CREATE INDEX idx_reports_dispatch ON reports(dispatch_status, updated_at);
CREATE INDEX idx_reports_retention ON reports(closed_at) WHERE closed_at IS NOT NULL;
CREATE UNIQUE INDEX idx_reports_external_issue ON reports(external_issue_url) WHERE external_issue_url IS NOT NULL;
CREATE INDEX idx_reports_external_issue_dispatch ON reports(external_issue_dispatch_status, updated_at);

PRAGMA foreign_keys = ON;
