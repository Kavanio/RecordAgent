-- 无正文的远端目标与重置进度必须先于本地来源清理持久化。
CREATE TABLE memory_deletion (
    space_id TEXT NOT NULL REFERENCES memory_space(id),
    document_id TEXT NOT NULL,
    recipient_fingerprint TEXT NOT NULL,
    operation_id TEXT NOT NULL,
    reset_id TEXT,
    state TEXT NOT NULL CHECK(state IN ('TRACKED','PENDING','VERIFIED','FAILED','UNKNOWN')),
    PRIMARY KEY(space_id,document_id)
);
CREATE TABLE memory_reset (
    space_id TEXT NOT NULL REFERENCES memory_space(id),
    reset_id TEXT NOT NULL,
    remote_status TEXT NOT NULL,
    PRIMARY KEY(space_id,reset_id)
);
