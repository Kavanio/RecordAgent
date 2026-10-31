-- 扩展 life_nudge 支持 M6 现实信息源主动提醒类型，并新增信号观测基线表。
CREATE TABLE life_nudge_new (
 id TEXT PRIMARY KEY, owner_id TEXT NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN ('IDLE_CHECKIN','WEATHER_ALERT','WEATHER_RECOVERY','EXPENSE_ALERT','SERVER_ALERT','SERVER_RECOVERY','MAIL_ALERT')),
 dedup_key TEXT NOT NULL, text TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('PENDING','SENDING','SENT','FAILED','UNKNOWN','CANCELLED')),
 target_fingerprint TEXT NOT NULL, message_id TEXT NOT NULL DEFAULT '',
 created_at TEXT NOT NULL, sent_at TEXT, feedback TEXT NOT NULL DEFAULT '', feedback_at TEXT,
 UNIQUE(owner_id,kind,dedup_key)
);
INSERT INTO life_nudge_new SELECT * FROM life_nudge;
DROP TABLE life_nudge;
ALTER TABLE life_nudge_new RENAME TO life_nudge;
CREATE INDEX life_nudge_owner ON life_nudge(owner_id,created_at);

CREATE TABLE life_signal_state (
 owner_id TEXT NOT NULL,
 signal_key TEXT NOT NULL CHECK(signal_key IN ('WEATHER','EXPENSE','SERVER','MAIL')),
 baseline_value TEXT NOT NULL,
 active_event_key TEXT NOT NULL DEFAULT '',
 observed_at TEXT NOT NULL,
 PRIMARY KEY(owner_id,signal_key)
);
