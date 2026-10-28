-- 经历记忆控制账本：原文继续由 transcript 管理；凭据和业务数据不在本域保存。
CREATE TABLE memory_space (
    id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL,
    environment TEXT NOT NULL,
    UNIQUE(owner_id, environment)
);
CREATE TABLE memory_settings (
    space_id TEXT PRIMARY KEY REFERENCES memory_space(id),
    version INTEGER NOT NULL DEFAULT 0,
    auto_save INTEGER NOT NULL DEFAULT 0 CHECK(auto_save IN (0,1)),
    auto_recall INTEGER NOT NULL DEFAULT 0 CHECK(auto_recall IN (0,1))
);
CREATE TABLE memory_authorization (
    space_id TEXT PRIMARY KEY REFERENCES memory_space(id),
    revision INTEGER NOT NULL DEFAULT 0,
    active INTEGER NOT NULL DEFAULT 0 CHECK(active IN (0,1)),
    recipient_fingerprint TEXT NOT NULL DEFAULT '',
    allow_save INTEGER NOT NULL DEFAULT 0,
    allow_recall INTEGER NOT NULL DEFAULT 0,
    channels TEXT NOT NULL DEFAULT '',
    effective_at TEXT,
    retention_days INTEGER NOT NULL DEFAULT 0,
    daily_write_limit INTEGER NOT NULL DEFAULT 0,
    daily_query_limit INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE memory_source_document (
    id TEXT PRIMARY KEY,
    space_id TEXT NOT NULL REFERENCES memory_space(id),
    generation INTEGER NOT NULL,
    session_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    message_id TEXT NOT NULL,
    occurred_at TEXT NOT NULL,
    channel TEXT NOT NULL CHECK(channel IN ('DESKTOP','FEISHU')),
    content_hash TEXT NOT NULL,
    authorization_revision INTEGER NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('CAPTURED','REVOKED')),
    document_id TEXT NOT NULL UNIQUE,
    recipient_fingerprint TEXT NOT NULL,
    remote_operation_id TEXT,
    remote_possible INTEGER NOT NULL DEFAULT 0,
    UNIQUE(space_id,generation,session_id,turn_id)
);
CREATE INDEX memory_source_space ON memory_source_document(space_id,state);
CREATE TABLE memory_command (
    space_id TEXT NOT NULL REFERENCES memory_space(id),
    request_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    payload_hash TEXT NOT NULL,
    result_revision INTEGER NOT NULL,
    effective_at TEXT NOT NULL,
    PRIMARY KEY(space_id,request_id)
);
