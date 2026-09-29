CREATE TABLE record_agent.study_category (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES record_agent.app_user(id),
    name VARCHAR(80) NOT NULL CHECK (length(btrim(name)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_id, id),
    UNIQUE (owner_id, name)
);

-- 完整时间点用于跨日拆分；自然日由 started_at 与 timezone 计算，不另存重复投影。
CREATE TABLE record_agent.study_session (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES record_agent.app_user(id),
    title VARCHAR(240) NOT NULL CHECK (length(btrim(title)) > 0),
    category_id UUID,
    location VARCHAR(240),
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    timezone VARCHAR(64) NOT NULL,
    source VARCHAR(24) NOT NULL DEFAULT 'manual',
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_id, id),
    CONSTRAINT fk_study_session_category FOREIGN KEY (owner_id, category_id)
        REFERENCES record_agent.study_category(owner_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_study_session_interval CHECK (ended_at IS NULL OR ended_at > started_at)
);
CREATE UNIQUE INDEX uq_study_active_owner ON record_agent.study_session (owner_id)
    WHERE ended_at IS NULL;
CREATE INDEX idx_study_session_owner_started
    ON record_agent.study_session (owner_id, started_at DESC);
CREATE INDEX idx_study_session_owner_ended
    ON record_agent.study_session (owner_id, ended_at)
    WHERE ended_at IS NOT NULL;
