-- 长期召回模式与独立运行保护；迁移本身不扩大任何既有授权的用途或范围。
ALTER TABLE memory_authorization ADD COLUMN policy_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE memory_authorization ADD COLUMN recall_mode TEXT NOT NULL DEFAULT 'RECENT_DAYS';
-- 运行保护与用途授权分开保存：调整节流不改变授权 revision，也不使已积累来源失效。
CREATE TABLE memory_runtime_policy (
    space_id TEXT PRIMARY KEY REFERENCES memory_space(id),
    version INTEGER NOT NULL DEFAULT 0,
    batch_size INTEGER NOT NULL DEFAULT 5,
    daily_write_limit INTEGER NOT NULL DEFAULT 500,
    daily_status_limit INTEGER NOT NULL DEFAULT 2000,
    daily_recall_limit INTEGER NOT NULL DEFAULT 200,
    CHECK(batch_size BETWEEN 1 AND 20),
    CHECK(daily_write_limit BETWEEN 1 AND 100000),
    CHECK(daily_status_limit BETWEEN 1 AND 100000),
    CHECK(daily_recall_limit BETWEEN 1 AND 100000)
);
INSERT INTO memory_runtime_policy(space_id) SELECT id FROM memory_space;
-- 状态核对与真实召回分开计量；query_calls 只保留 V1 授权原有的合并计数。
ALTER TABLE memory_sync_usage ADD COLUMN status_calls INTEGER NOT NULL DEFAULT 0;
ALTER TABLE memory_sync_usage ADD COLUMN recall_calls INTEGER NOT NULL DEFAULT 0;
-- 只累计已授权空间被过滤的原因，不保存被排除的用户正文。
CREATE TABLE memory_source_skip (
    space_id TEXT NOT NULL REFERENCES memory_space(id),
    day TEXT NOT NULL,
    reason TEXT NOT NULL,
    skipped INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(space_id,day,reason)
);
