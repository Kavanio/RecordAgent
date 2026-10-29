-- 任务只引用获准来源，不能复制用户原文；稳定操作 ID 在第一次任务创建时固定。
CREATE TABLE memory_sync_operation (
    id TEXT PRIMARY KEY,
    source_id TEXT NOT NULL UNIQUE REFERENCES memory_source_document(id) ON DELETE CASCADE,
    state TEXT NOT NULL CHECK(state IN ('PENDING','CLAIMED','SUBMITTED','SUCCEEDED','FAILED','UNKNOWN','RETRY_WAIT','CANCELLED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    queries INTEGER NOT NULL DEFAULT 0,
    next_attempt_at INTEGER NOT NULL DEFAULT 0,
    claim_token TEXT,
    claimed_until INTEGER NOT NULL DEFAULT 0,
    error_code TEXT NOT NULL DEFAULT ''
);
CREATE INDEX memory_sync_due ON memory_sync_operation(state,next_attempt_at,claimed_until);
CREATE TABLE memory_sync_cursor (
    space_id TEXT PRIMARY KEY REFERENCES memory_space(id),
    last_rowid INTEGER NOT NULL DEFAULT 0
);
-- 调用预占不随重置退还，防止通过重置绕过当天额度；这不是供应商实际 Token 用量。
CREATE TABLE memory_sync_usage (
    space_id TEXT NOT NULL REFERENCES memory_space(id),
    day TEXT NOT NULL,
    write_calls INTEGER NOT NULL DEFAULT 0,
    query_calls INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(space_id,day)
);
