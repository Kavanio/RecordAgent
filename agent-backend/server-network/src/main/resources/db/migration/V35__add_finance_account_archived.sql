-- 支持停用并归档余额为零的资产账户，保留历史流水及统计口径
ALTER TABLE finance_account ADD COLUMN archived INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1));
ALTER TABLE finance_account ADD COLUMN archived_at TEXT;

CREATE INDEX idx_finance_account_owner_active
    ON finance_account (owner_id, archived, created_at);
