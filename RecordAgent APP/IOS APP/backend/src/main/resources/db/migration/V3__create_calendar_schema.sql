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
