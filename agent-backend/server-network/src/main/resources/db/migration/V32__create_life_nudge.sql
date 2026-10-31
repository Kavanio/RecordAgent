-- 与复盘内容解耦的主动短消息（首个类型为久未互动问候）；固定文案不含个人资料。
CREATE TABLE life_nudge (
 id TEXT PRIMARY KEY, owner_id TEXT NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN ('IDLE_CHECKIN')),
 dedup_key TEXT NOT NULL, text TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('PENDING','SENDING','SENT','FAILED','UNKNOWN','CANCELLED')),
 target_fingerprint TEXT NOT NULL, message_id TEXT NOT NULL DEFAULT '',
 created_at TEXT NOT NULL, sent_at TEXT, feedback TEXT NOT NULL DEFAULT '', feedback_at TEXT,
 UNIQUE(owner_id,kind,dedup_key)
);
CREATE INDEX life_nudge_owner ON life_nudge(owner_id,created_at);
