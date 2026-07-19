ALTER TABLE reports ADD COLUMN github_user_pending INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_reports_github_user_pending
  ON reports(github_user_pending, created_at)
  WHERE github_user_pending = 1;
