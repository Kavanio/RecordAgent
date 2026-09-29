-- 数据库仅存元数据与不透明 storage_key；二进制由 BlobStore Adapter 持有。
CREATE TABLE record_agent.file_asset (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES record_agent.app_user(id),
    original_name VARCHAR(255) NOT NULL CHECK (length(btrim(original_name)) > 0),
    media_type VARCHAR(127) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256 CHAR(64) CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    backend_type VARCHAR(24) NOT NULL CHECK (backend_type IN ('local', 'object')),
    storage_key TEXT NOT NULL UNIQUE,
    status VARCHAR(24) NOT NULL CHECK (status IN ('pending', 'available', 'deleted')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    UNIQUE (owner_id, id),
    CONSTRAINT ck_file_asset_deleted CHECK ((status = 'deleted') = (deleted_at IS NOT NULL))
);
CREATE INDEX idx_file_asset_owner_state
    ON record_agent.file_asset (owner_id, status, created_at DESC);

-- 分开绑定表，让数据库能够直接校验所属用户与目标对象，而非多态 domain_id。
CREATE TABLE record_agent.record_file (
    owner_id UUID NOT NULL REFERENCES record_agent.app_user(id),
    record_id UUID NOT NULL,
    file_id UUID NOT NULL,
    role VARCHAR(24) NOT NULL DEFAULT 'attachment' CHECK (role IN ('attachment', 'cover')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, record_id, file_id),
    CONSTRAINT fk_record_file_record FOREIGN KEY (owner_id, record_id)
        REFERENCES record_agent.record_entry(owner_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_record_file_asset FOREIGN KEY (owner_id, file_id)
        REFERENCES record_agent.file_asset(owner_id, id) ON DELETE RESTRICT
);
CREATE INDEX idx_record_file_asset ON record_agent.record_file (owner_id, file_id);

CREATE TABLE record_agent.calendar_file (
    owner_id UUID NOT NULL REFERENCES record_agent.app_user(id),
    entry_id UUID NOT NULL,
    file_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, entry_id, file_id),
    CONSTRAINT fk_calendar_file_entry FOREIGN KEY (owner_id, entry_id)
        REFERENCES record_agent.calendar_entry(owner_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_calendar_file_asset FOREIGN KEY (owner_id, file_id)
        REFERENCES record_agent.file_asset(owner_id, id) ON DELETE RESTRICT
);
CREATE INDEX idx_calendar_file_asset ON record_agent.calendar_file (owner_id, file_id);

-- 头像和背景图也走相同的受控文件资产模型。
CREATE TABLE record_agent.user_media (
    owner_id UUID PRIMARY KEY REFERENCES record_agent.app_user(id) ON DELETE CASCADE,
    avatar_file_id UUID,
    background_file_id UUID,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_user_media_avatar FOREIGN KEY (owner_id, avatar_file_id)
        REFERENCES record_agent.file_asset(owner_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_media_background FOREIGN KEY (owner_id, background_file_id)
        REFERENCES record_agent.file_asset(owner_id, id) ON DELETE RESTRICT
);
