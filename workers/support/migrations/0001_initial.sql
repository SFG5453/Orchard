PRAGMA foreign_keys = ON;

CREATE TABLE clients (
  id TEXT PRIMARY KEY,
  secret_hash TEXT NOT NULL,
  email TEXT NOT NULL DEFAULT '',
  email_verified_at TEXT,
  created_at TEXT NOT NULL,
  last_seen_at TEXT NOT NULL
);

CREATE TABLE email_verifications (
  id TEXT PRIMARY KEY,
  client_id TEXT NOT NULL,
  token_hash TEXT NOT NULL UNIQUE,
  email TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  created_at TEXT NOT NULL,
  FOREIGN KEY(client_id) REFERENCES clients(id) ON DELETE CASCADE
);

CREATE TABLE reports (
  id TEXT PRIMARY KEY,
  client_id TEXT NOT NULL,
  type TEXT NOT NULL CHECK(type IN ('bug', 'feature', 'feedback')),
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
  FOREIGN KEY(client_id) REFERENCES clients(id) ON DELETE CASCADE
);

CREATE INDEX idx_reports_client_updated ON reports(client_id, updated_at DESC);
CREATE INDEX idx_reports_thread ON reports(discord_thread_id) WHERE discord_thread_id IS NOT NULL;
CREATE INDEX idx_reports_dispatch ON reports(dispatch_status, updated_at);
CREATE INDEX idx_reports_retention ON reports(closed_at) WHERE closed_at IS NOT NULL;

CREATE TABLE messages (
  id TEXT PRIMARY KEY,
  report_id TEXT NOT NULL,
  sender TEXT NOT NULL CHECK(sender IN ('user', 'staff', 'system')),
  body TEXT NOT NULL,
  discord_message_id TEXT,
  created_at TEXT NOT NULL,
  FOREIGN KEY(report_id) REFERENCES reports(id) ON DELETE CASCADE
);

CREATE INDEX idx_messages_report_created ON messages(report_id, created_at);

CREATE TABLE attachments (
  id TEXT PRIMARY KEY,
  report_id TEXT NOT NULL,
  message_id TEXT NOT NULL,
  r2_key TEXT NOT NULL UNIQUE,
  filename TEXT NOT NULL,
  content_type TEXT NOT NULL,
  byte_size INTEGER NOT NULL,
  created_at TEXT NOT NULL,
  FOREIGN KEY(report_id) REFERENCES reports(id) ON DELETE CASCADE,
  FOREIGN KEY(message_id) REFERENCES messages(id) ON DELETE CASCADE
);

CREATE INDEX idx_attachments_report ON attachments(report_id);

CREATE TABLE rate_events (
  key TEXT NOT NULL,
  action TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE INDEX idx_rate_events_lookup ON rate_events(key, action, created_at);

