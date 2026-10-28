-- 仅记录外部操作身份与清理进度，不复制 task_payload 或记忆正文。
CREATE TABLE memory_remote_operation (
    operation_id TEXT PRIMARY KEY,
    space_id TEXT NOT NULL,
    document_id TEXT NOT NULL,
    recipient_fingerprint TEXT NOT NULL,
    payload_hash TEXT NOT NULL,
    acknowledged INTEGER NOT NULL DEFAULT 0,
    parent_status TEXT NOT NULL DEFAULT 'unknown',
    terminal_seen INTEGER NOT NULL DEFAULT 0,
    document_delete_requested INTEGER NOT NULL DEFAULT 0,
    document_deleted INTEGER NOT NULL DEFAULT 0,
    facts_checked INTEGER NOT NULL DEFAULT 0,
    parent_delete_requested INTEGER NOT NULL DEFAULT 0,
    parent_deleted INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY(space_id,document_id) REFERENCES memory_deletion(space_id,document_id)
);
CREATE INDEX memory_remote_space ON memory_remote_operation(space_id,document_id);
CREATE TABLE memory_remote_child (
    operation_id TEXT NOT NULL REFERENCES memory_remote_operation(operation_id) ON DELETE CASCADE,
    child_id TEXT NOT NULL,
    status TEXT NOT NULL,
    delete_requested INTEGER NOT NULL DEFAULT 0,
    deleted INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(operation_id,child_id)
);
