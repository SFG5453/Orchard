DROP INDEX IF EXISTS idx_reports_gitea_issue;
DROP INDEX IF EXISTS idx_reports_gitea_dispatch;

ALTER TABLE reports RENAME COLUMN gitea_issue_number TO external_issue_number;
ALTER TABLE reports RENAME COLUMN gitea_issue_url TO external_issue_url;
ALTER TABLE reports RENAME COLUMN gitea_dispatch_status TO external_issue_dispatch_status;

CREATE UNIQUE INDEX idx_reports_external_issue ON reports(external_issue_url) WHERE external_issue_url IS NOT NULL;
CREATE INDEX idx_reports_external_issue_dispatch ON reports(external_issue_dispatch_status, updated_at);
