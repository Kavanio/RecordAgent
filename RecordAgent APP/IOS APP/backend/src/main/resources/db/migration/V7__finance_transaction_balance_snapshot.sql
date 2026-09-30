-- 只记录提交时的余额；既有流水没有可靠快照，保留 NULL，禁止倒推。
ALTER TABLE record.finance_transaction ADD COLUMN balance_after_minor BIGINT;
ALTER TABLE record.finance_transaction ADD CONSTRAINT finance_balance_after_nonnegative
    CHECK (balance_after_minor IS NULL OR balance_after_minor >= 0);
COMMENT ON COLUMN record.finance_transaction.balance_after_minor IS '交易提交后账户余额（最小货币单位），历史未知为NULL';
