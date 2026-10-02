ALTER TABLE replay_jobs ADD COLUMN cursor_id VARCHAR(100) NOT NULL DEFAULT '';
ALTER TABLE replay_jobs ADD COLUMN skipped INTEGER NOT NULL DEFAULT 0;
CREATE INDEX ix_replay_pending ON replay_jobs(status,created_at);
