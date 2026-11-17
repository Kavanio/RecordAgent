-- 对话档案（素材）元数据表：每份聊天产生的图片或文件对应唯一 file_id（关联 file_asset），本地按多级目录分类落盘。
CREATE TABLE chat_material (
    id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL,
    file_id TEXT NOT NULL UNIQUE,
    channel TEXT NOT NULL,
    session_kind TEXT NOT NULL,
    session_id TEXT NOT NULL,
    project_id TEXT,
    session_title_snapshot TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    category TEXT NOT NULL,
    sub_type TEXT NOT NULL,
    original_name TEXT NOT NULL,
    media_type TEXT NOT NULL,
    size_bytes INTEGER NOT NULL CHECK (size_bytes >= 0),
    sha256 TEXT,
    archive_path TEXT NOT NULL,
    created_date TEXT NOT NULL,
    created_at TEXT NOT NULL,
    deleted_at TEXT,
    FOREIGN KEY (file_id) REFERENCES file_asset(id) ON DELETE CASCADE
);

CREATE INDEX idx_chat_material_owner_created
    ON chat_material (owner_id, deleted_at, created_at DESC);

CREATE INDEX idx_chat_material_filter
    ON chat_material (owner_id, deleted_at, category, channel, session_kind, created_date);

CREATE INDEX idx_chat_material_session
    ON chat_material (owner_id, deleted_at, session_id, created_at DESC);
