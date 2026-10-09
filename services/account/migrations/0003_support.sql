-- Bug reports filed as GitHub issues, plus the GitHub identity they require.

-- GitHub identities keep the numeric id in subject; login and avatar are for display.
ALTER TABLE identities ADD COLUMN login TEXT NOT NULL DEFAULT '';
ALTER TABLE identities ADD COLUMN picture TEXT NOT NULL DEFAULT '';

-- GitHub link attempts waiting on the browser. Keyed by the state sent to GitHub.
CREATE TABLE github_links (
    state TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at INTEGER NOT NULL
);

-- One row per filed issue. repository is stored so changing GITHUB_REPOSITORY
-- never orphans older reports.
CREATE TABLE support_reports (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    github_id TEXT NOT NULL,
    repository TEXT NOT NULL,
    issue_number INTEGER NOT NULL,
    issue_url TEXT NOT NULL,
    kind TEXT NOT NULL,
    title TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'open',
    state_reason TEXT NOT NULL DEFAULT '',
    screenshot_key TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    read_at INTEGER NOT NULL,
    synced_at INTEGER NOT NULL DEFAULT 0,
    -- Timeline cursor: last page fetched and its ETag for conditional requests.
    timeline_page INTEGER NOT NULL DEFAULT 1,
    timeline_etag TEXT NOT NULL DEFAULT '',
    UNIQUE (repository, issue_number)
);
CREATE INDEX support_reports_user ON support_reports(user_id, updated_at);
CREATE INDEX support_reports_sync ON support_reports(synced_at);

-- Issue activity mirrored from the GitHub timeline. added_at is when Orchard
-- saw it, which is what unread counts compare against.
CREATE TABLE support_events (
    report_id TEXT NOT NULL REFERENCES support_reports(id) ON DELETE CASCADE,
    key TEXT NOT NULL,
    kind TEXT NOT NULL,
    actor TEXT NOT NULL DEFAULT '',
    actor_avatar TEXT NOT NULL DEFAULT '',
    maintainer INTEGER NOT NULL DEFAULT 0,
    reporter INTEGER NOT NULL DEFAULT 0,
    notify INTEGER NOT NULL DEFAULT 1,
    title TEXT NOT NULL,
    body TEXT NOT NULL DEFAULT '',
    url TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL,
    added_at INTEGER NOT NULL,
    PRIMARY KEY (report_id, key)
);
CREATE INDEX support_events_report ON support_events(report_id, created_at);
