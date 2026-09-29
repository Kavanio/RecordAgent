-- 账户 ID 是唯一的业务所有者标识；邮箱只用于登录，不参与业务外键。
CREATE TABLE record_agent.app_user (
    id UUID PRIMARY KEY,
    email_normalized VARCHAR(320) NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    display_name_normalized VARCHAR(80) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled')),
    locale VARCHAR(16),
    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_app_user_email CHECK (email_normalized = lower(btrim(email_normalized)) AND length(email_normalized) > 3),
    CONSTRAINT ck_app_user_name CHECK (display_name_normalized = lower(btrim(display_name_normalized)) AND length(display_name_normalized) > 0)
);

-- 仅保存验证码摘要；重放、过期与尝试次数由账户服务在事务中判定。
CREATE TABLE record_agent.email_challenge (
    id UUID PRIMARY KEY,
    email_normalized VARCHAR(320) NOT NULL,
    purpose VARCHAR(16) NOT NULL CHECK (purpose IN ('register', 'login', 'reset_password')),
    code_hash TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_email_challenge_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_email_challenge_consumed CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);
CREATE INDEX idx_email_challenge_lookup
    ON record_agent.email_challenge (email_normalized, purpose, created_at DESC)
    WHERE consumed_at IS NULL;

-- 刷新凭据摘要可轮换、撤销；访问令牌是否有状态由认证实现决定。
CREATE TABLE record_agent.auth_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES record_agent.app_user(id) ON DELETE CASCADE,
    refresh_token_hash TEXT NOT NULL UNIQUE,
    device_label VARCHAR(120),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ,
    CONSTRAINT ck_auth_session_expiry CHECK (expires_at > created_at)
);
CREATE INDEX idx_auth_session_user ON record_agent.auth_session (user_id, created_at DESC);
