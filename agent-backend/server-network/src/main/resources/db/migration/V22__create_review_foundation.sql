-- 长期复盘基础：默认关闭，授权、事实与用户目标分域保存，不启用模型调用。
CREATE TABLE review_settings (
    owner_id TEXT PRIMARY KEY,
    enabled INTEGER NOT NULL DEFAULT 0 CHECK (enabled IN (0, 1)),
    periods TEXT NOT NULL,
    domains TEXT NOT NULL,
    history_days INTEGER NOT NULL CHECK (history_days BETWEEN 1 AND 180),
    daily_limit INTEGER NOT NULL CHECK (daily_limit BETWEEN 1 AND 3),
    provider_id TEXT NOT NULL,
    model_name TEXT NOT NULL,
    connection_fingerprint TEXT NOT NULL,
    revision INTEGER NOT NULL CHECK (revision > 0),
    updated_at TEXT NOT NULL
);
CREATE TABLE review_grant (
    id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL,
    policy_version TEXT NOT NULL,
    periods TEXT NOT NULL,
    domains TEXT NOT NULL,
    history_days INTEGER NOT NULL CHECK (history_days BETWEEN 1 AND 180),
    daily_limit INTEGER NOT NULL CHECK (daily_limit BETWEEN 1 AND 3),
    connection_fingerprint TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('ACTIVE', 'REVOKED')),
    revision INTEGER NOT NULL CHECK (revision > 0),
    created_at TEXT NOT NULL,
    revoked_at TEXT
);
CREATE UNIQUE INDEX review_one_active_grant ON review_grant(owner_id) WHERE state = 'ACTIVE';
CREATE INDEX review_grant_history ON review_grant(owner_id, created_at DESC);

CREATE TABLE review_snapshot (
    id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL,
    grant_id TEXT NOT NULL REFERENCES review_grant(id),
    period_from TEXT NOT NULL,
    period_to TEXT NOT NULL,
    timezone TEXT NOT NULL,
    source_as_of TEXT NOT NULL,
    metric_version INTEGER NOT NULL,
    fingerprint TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    UNIQUE(owner_id, period_from, period_to, timezone, fingerprint)
);
CREATE INDEX review_snapshot_history ON review_snapshot(owner_id, source_as_of DESC, id);
CREATE TABLE review_fact (
    snapshot_id TEXT NOT NULL REFERENCES review_snapshot(id) ON DELETE CASCADE,
    domain TEXT NOT NULL,
    metric_key TEXT NOT NULL,
    subject_id TEXT NOT NULL,
    currency TEXT NOT NULL,
    unit TEXT NOT NULL CHECK (unit IN ('MONEY_MINOR', 'COUNT', 'SECONDS')),
    value INTEGER NOT NULL,
    PRIMARY KEY(snapshot_id, domain, metric_key, subject_id, currency)
);
CREATE TABLE review_source_status (
    snapshot_id TEXT NOT NULL REFERENCES review_snapshot(id) ON DELETE CASCADE,
    domain TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('AVAILABLE', 'EMPTY', 'UNAVAILABLE')),
    PRIMARY KEY(snapshot_id, domain)
);
CREATE TABLE review_goal (
    id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL,
    title TEXT NOT NULL,
    constraint_text TEXT NOT NULL,
    success_criterion TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('ACTIVE', 'ARCHIVED')),
    revision INTEGER NOT NULL CHECK (revision > 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX review_goal_owner ON review_goal(owner_id, state, created_at, id);
