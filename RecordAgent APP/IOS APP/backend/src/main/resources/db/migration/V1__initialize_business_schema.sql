-- RecordAgent 新建数据库完整基线。仅适用于未执行过旧版 Flyway V1 的空库。
-- 已有迁移历史的数据库必须另行制定兼容迁移，不能直接替换已执行脚本。
CREATE SCHEMA IF NOT EXISTS record_agent;

-- 账户领域
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

-- 日历领域
-- 日历自己的事实只有待办和日程；收支、手记、学习由各领域按日投影。
CREATE TABLE record_agent.calendar_entry (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES record_agent.app_user(id),
    entry_type VARCHAR(16) NOT NULL CHECK (entry_type IN ('todo', 'schedule')),
    event_date DATE NOT NULL,
    title VARCHAR(240) NOT NULL CHECK (length(btrim(title)) > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'confirmed' CHECK (status IN ('confirmed', 'cancelled')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_id, id),
    UNIQUE (id, entry_type)
);
CREATE INDEX idx_calendar_entry_owner_date
    ON record_agent.calendar_entry (owner_id, event_date, created_at DESC);

CREATE TABLE record_agent.calendar_todo (
    entry_id UUID PRIMARY KEY,
    entry_type VARCHAR(16) NOT NULL DEFAULT 'todo' CHECK (entry_type = 'todo'),
    todo_time TIME,
    priority VARCHAR(16) NOT NULL DEFAULT 'normal' CHECK (priority IN ('low', 'normal', 'high')),
    recurrence VARCHAR(16) NOT NULL DEFAULT 'none' CHECK (recurrence IN ('none', 'daily', 'weekly', 'monthly')),
    recurrence_weekday SMALLINT,
    recurrence_month_day SMALLINT,
    CONSTRAINT fk_calendar_todo_entry FOREIGN KEY (entry_id, entry_type)
        REFERENCES record_agent.calendar_entry(id, entry_type) ON DELETE CASCADE,
    CONSTRAINT ck_calendar_todo_recurrence CHECK (
        (recurrence IN ('none', 'daily') AND recurrence_weekday IS NULL AND recurrence_month_day IS NULL)
        OR (recurrence = 'weekly' AND recurrence_weekday BETWEEN 1 AND 7 AND recurrence_month_day IS NULL)
        OR (recurrence = 'monthly' AND recurrence_weekday IS NULL AND recurrence_month_day = 31)
    )
);

-- period_start：一次性/每日为发生日，每周为周一，每月为月初；月末只是展示发生日。
CREATE TABLE record_agent.todo_completion (
    entry_id UUID NOT NULL REFERENCES record_agent.calendar_todo(entry_id) ON DELETE CASCADE,
    period_start DATE NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (entry_id, period_start)
);

CREATE TABLE record_agent.calendar_schedule (
    entry_id UUID PRIMARY KEY,
    entry_type VARCHAR(16) NOT NULL DEFAULT 'schedule' CHECK (entry_type = 'schedule'),
    start_time TIME,
    end_time TIME,
    timezone VARCHAR(64) NOT NULL,
    location VARCHAR(240),
    recurrence_rule TEXT,
    CONSTRAINT fk_calendar_schedule_entry FOREIGN KEY (entry_id, entry_type)
        REFERENCES record_agent.calendar_entry(id, entry_type) ON DELETE CASCADE,
    CONSTRAINT ck_calendar_schedule_time CHECK (start_time IS NULL OR end_time IS NULL OR end_time > start_time)
);

CREATE TABLE record_agent.calendar_preference (
    owner_id UUID PRIMARY KEY REFERENCES record_agent.app_user(id) ON DELETE CASCADE,
    weekly_note_visible BOOLEAN NOT NULL DEFAULT true,
    monthly_note_visible BOOLEAN NOT NULL DEFAULT true,
    weekly_note_expanded BOOLEAN NOT NULL DEFAULT true,
    monthly_note_expanded BOOLEAN NOT NULL DEFAULT true,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 财务领域
-- 资产账户与流水同属一个用户；余额调整与流水必须由服务层在同一事务提交。
CREATE TABLE record_agent.finance_account (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES record_agent.app_user(id),
    name VARCHAR(120) NOT NULL CHECK (length(btrim(name)) > 0),
    account_type VARCHAR(32) NOT NULL,
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    balance_minor BIGINT NOT NULL DEFAULT 0 CHECK (balance_minor >= 0),
    interest_enabled BOOLEAN NOT NULL DEFAULT false,
    annual_rate_percent NUMERIC(9, 6) NOT NULL DEFAULT 0 CHECK (annual_rate_percent >= 0),
    last_accrual_date DATE,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_id, id),
    UNIQUE (owner_id, name)
);
CREATE INDEX idx_finance_account_owner ON record_agent.finance_account (owner_id, created_at);

CREATE TABLE record_agent.finance_category (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES record_agent.app_user(id),
    transaction_type VARCHAR(16) NOT NULL CHECK (transaction_type IN ('expense', 'income')),
    name VARCHAR(80) NOT NULL CHECK (length(btrim(name)) > 0),
    color VARCHAR(7) CHECK (color ~ '^#[0-9A-Fa-f]{6}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_id, id),
    UNIQUE (owner_id, id, transaction_type),
    UNIQUE (owner_id, transaction_type, name)
);

-- 一笔流水对应一个账户的余额变化；划账用同一 group_id 的出入两笔表达。
CREATE TABLE record_agent.finance_transaction (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES record_agent.app_user(id),
    account_id UUID NOT NULL,
    group_id UUID,
    transaction_date DATE NOT NULL,
    transaction_time TIME NOT NULL,
    transaction_type VARCHAR(24) NOT NULL CHECK (transaction_type IN (
        'expense', 'income', 'transfer_out', 'transfer_in',
        'adjustment_decrease', 'adjustment_increase', 'yield')),
    category_id UUID,
    note TEXT NOT NULL DEFAULT '',
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    source VARCHAR(24) NOT NULL DEFAULT 'manual',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_finance_transaction_account FOREIGN KEY (owner_id, account_id)
        REFERENCES record_agent.finance_account(owner_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_finance_transaction_category FOREIGN KEY (owner_id, category_id, transaction_type)
        REFERENCES record_agent.finance_category(owner_id, id, transaction_type) ON DELETE RESTRICT,
    CONSTRAINT ck_finance_transaction_group CHECK (
        (transaction_type IN ('transfer_out', 'transfer_in')) = (group_id IS NOT NULL)),
    CONSTRAINT ck_finance_transaction_category CHECK (
        category_id IS NULL OR transaction_type IN ('expense', 'income'))
);
CREATE INDEX idx_finance_transaction_owner_date
    ON record_agent.finance_transaction (owner_id, transaction_date DESC, transaction_time DESC, id DESC);
CREATE INDEX idx_finance_transaction_account_date
    ON record_agent.finance_transaction (owner_id, account_id, transaction_date DESC);
CREATE UNIQUE INDEX uq_finance_transfer_leg
    ON record_agent.finance_transaction (group_id, transaction_type)
    WHERE group_id IS NOT NULL;
CREATE UNIQUE INDEX uq_finance_daily_yield
    ON record_agent.finance_transaction (account_id, transaction_date)
    WHERE transaction_type = 'yield';

-- 资料领域
-- 手记与资料条目使用同一张表、同一个 ID；日历只查询 journal 类型投影。
CREATE TABLE record_agent.record_tab (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES record_agent.app_user(id),
    name VARCHAR(80) NOT NULL CHECK (length(btrim(name)) > 0),
    system_key VARCHAR(32),
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_id, id),
    UNIQUE (owner_id, name),
    UNIQUE (owner_id, system_key)
);
CREATE INDEX idx_record_tab_owner_order ON record_agent.record_tab (owner_id, sort_order, created_at);

CREATE TABLE record_agent.record_entry (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES record_agent.app_user(id),
    tab_id UUID,
    record_date DATE NOT NULL,
    record_type VARCHAR(24) NOT NULL CHECK (record_type IN (
        'quick', 'learning', 'weekly_review', 'reading', 'journal')),
    title VARCHAR(240),
    content_html TEXT NOT NULL DEFAULT '',
    content_text TEXT NOT NULL DEFAULT '',
    week_year INTEGER,
    week_number SMALLINT,
    pinned BOOLEAN NOT NULL DEFAULT false,
    favorite BOOLEAN NOT NULL DEFAULT false,
    archived BOOLEAN NOT NULL DEFAULT false,
    trashed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_id, id),
    CONSTRAINT fk_record_entry_tab FOREIGN KEY (owner_id, tab_id)
        REFERENCES record_agent.record_tab(owner_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_record_week CHECK (
        (record_type = 'weekly_review' AND week_year IS NOT NULL AND week_number BETWEEN 1 AND 53)
        OR (record_type <> 'weekly_review' AND week_year IS NULL AND week_number IS NULL))
);
CREATE INDEX idx_record_entry_owner_date
    ON record_agent.record_entry (owner_id, record_date DESC, updated_at DESC)
    WHERE trashed_at IS NULL;
CREATE INDEX idx_record_entry_owner_tab
    ON record_agent.record_entry (owner_id, tab_id, record_date DESC)
    WHERE trashed_at IS NULL;
CREATE INDEX idx_record_entry_owner_type_state
    ON record_agent.record_entry (owner_id, record_type, archived, updated_at DESC)
    WHERE trashed_at IS NULL;
CREATE INDEX idx_record_entry_owner_week
    ON record_agent.record_entry (owner_id, week_year, week_number)
    WHERE record_type = 'weekly_review' AND trashed_at IS NULL;

CREATE TABLE record_agent.record_tag (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES record_agent.app_user(id),
    name VARCHAR(80) NOT NULL CHECK (length(btrim(name)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_id, id),
    UNIQUE (owner_id, name)
);

CREATE TABLE record_agent.record_entry_tag (
    owner_id UUID NOT NULL REFERENCES record_agent.app_user(id),
    record_id UUID NOT NULL,
    tag_id UUID NOT NULL,
    PRIMARY KEY (owner_id, record_id, tag_id),
    CONSTRAINT fk_record_entry_tag_entry FOREIGN KEY (owner_id, record_id)
        REFERENCES record_agent.record_entry(owner_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_record_entry_tag_tag FOREIGN KEY (owner_id, tag_id)
        REFERENCES record_agent.record_tag(owner_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_record_entry_tag_tag ON record_agent.record_entry_tag (owner_id, tag_id);

-- 学习领域
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

-- 文件领域
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
