ALTER TABLE reports ADD COLUMN gitea_issue_number INTEGER;
ALTER TABLE reports ADD COLUMN gitea_issue_url TEXT;
ALTER TABLE reports ADD COLUMN gitea_dispatch_status TEXT NOT NULL DEFAULT 'pending'
  CHECK(gitea_dispatch_status IN ('pending', 'sent', 'failed'));

CREATE UNIQUE INDEX idx_reports_gitea_issue ON reports(gitea_issue_url) WHERE gitea_issue_url IS NOT NULL;
CREATE INDEX idx_reports_gitea_dispatch ON reports(gitea_dispatch_status, updated_at);
