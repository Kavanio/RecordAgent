ALTER TABLE record.auth_session
    ADD COLUMN access_token_hash TEXT UNIQUE,
    ADD COLUMN access_expires_at TIMESTAMPTZ;

CREATE INDEX idx_auth_session_access ON record.auth_session (access_token_hash)
    WHERE revoked_at IS NULL;

ALTER TABLE record.calendar_entry ADD COLUMN detail TEXT;
