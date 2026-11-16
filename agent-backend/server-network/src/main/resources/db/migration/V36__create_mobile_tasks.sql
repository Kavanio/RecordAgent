-- 手机端本地通知提醒任务与送达观察回执；独立于桌面端自动任务（automation_task）。
CREATE TABLE mobile_task (
    id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL DEFAULT '',
    recurrence TEXT NOT NULL,
    hour INTEGER NOT NULL,
    minute INTEGER NOT NULL,
    weekday INTEGER,
    day_of_month INTEGER,
    once_date TEXT,
    timezone TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    version INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX idx_mobile_task_owner ON mobile_task(owner_id, enabled, updated_at);

CREATE TABLE mobile_task_delivery (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES mobile_task(id) ON DELETE CASCADE,
    owner_id TEXT NOT NULL,
    event_id TEXT NOT NULL,
    delivered_at INTEGER NOT NULL,
    UNIQUE(owner_id, task_id, event_id)
);

CREATE INDEX idx_mobile_task_delivery_task ON mobile_task_delivery(owner_id, task_id, delivered_at);
