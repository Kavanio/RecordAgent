-- 管理版本、独立纠正正文与无正文任务；不改写原始聊天。
ALTER TABLE memory_source_document ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
CREATE TABLE memory_correction (
    source_id TEXT PRIMARY KEY REFERENCES memory_source_document(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    corrected_text TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TABLE memory_management_operation (
    id TEXT PRIMARY KEY,
    space_id TEXT NOT NULL REFERENCES memory_space(id),
    source_id TEXT NOT NULL REFERENCES memory_source_document(id) ON DELETE CASCADE,
    request_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK(kind IN ('CORRECT','DELETE')),
    payload_hash TEXT NOT NULL,
    result_version INTEGER NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('PENDING','WAITING_REMOTE','SUCCEEDED','FAILED','UNKNOWN')),
    updated_at TEXT NOT NULL,
    UNIQUE(space_id,request_id)
);
CREATE INDEX memory_management_source ON memory_management_operation(space_id,source_id);
