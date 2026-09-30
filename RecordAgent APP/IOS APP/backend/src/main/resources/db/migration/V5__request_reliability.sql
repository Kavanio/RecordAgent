-- 收支幂等缓存与流水同事务登记；缓存仅用于重放响应，不作为余额事实来源。
CREATE TABLE record.finance_write_request (
    owner_id UUID NOT NULL REFERENCES record.app_user(id),
    request_id UUID NOT NULL,
    request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    transaction_id UUID NOT NULL REFERENCES record.finance_transaction(id),
    response_body JSONB NOT NULL CHECK (jsonb_typeof(response_body) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(owner_id, request_id)
);

-- 固定窗口限流由 PostgreSQL 原子维护，所有应用实例共享，不存邮箱或 IP 明文。
CREATE TABLE record.auth_rate_bucket (
    scope VARCHAR(32) NOT NULL,
    key_hash CHAR(64) NOT NULL CHECK (key_hash ~ '^[0-9a-f]{64}$'),
    window_start TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts INTEGER NOT NULL CHECK (attempts > 0),
    PRIMARY KEY(scope, key_hash)
);
CREATE INDEX idx_auth_rate_bucket_time ON record.auth_rate_bucket(window_start);
