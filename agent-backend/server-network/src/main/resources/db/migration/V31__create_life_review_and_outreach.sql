-- 生活复盘独立授权、不可变内容版本、统一投递和主动候选。
CREATE TABLE life_review_settings (
 owner_id TEXT PRIMARY KEY, revision INTEGER NOT NULL, enabled INTEGER NOT NULL DEFAULT 0,
 grant_id TEXT NOT NULL, settings_json TEXT NOT NULL
);
CREATE TABLE life_review_content (
 id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, review_day TEXT NOT NULL, version INTEGER NOT NULL,
 grant_id TEXT NOT NULL, settings_revision INTEGER NOT NULL, state TEXT NOT NULL,
 source_as_of TEXT NOT NULL, available_at TEXT NOT NULL, created_at TEXT NOT NULL,
 generation INTEGER NOT NULL, content_json TEXT NOT NULL,
 UNIQUE(owner_id,review_day,version)
);
CREATE INDEX life_review_pending ON life_review_content(state,created_at);
CREATE TABLE life_review_delivery (
 id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, content_id TEXT NOT NULL REFERENCES life_review_content(id),
 channel TEXT NOT NULL, state TEXT NOT NULL, target_fingerprint TEXT NOT NULL,
 message_id TEXT NOT NULL DEFAULT '', attempts INTEGER NOT NULL DEFAULT 0,
 updated_at TEXT NOT NULL, UNIQUE(content_id,channel)
);
CREATE TABLE life_outreach_candidate (
 id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, topic TEXT NOT NULL, novelty TEXT NOT NULL,
 source_id TEXT NOT NULL, content_id TEXT NOT NULL REFERENCES life_review_content(id),
 state TEXT NOT NULL, due_at TEXT NOT NULL, expires_at TEXT NOT NULL,
 created_at TEXT NOT NULL, sent_at TEXT, feedback TEXT NOT NULL DEFAULT '', feedback_at TEXT,
 event_kind TEXT NOT NULL DEFAULT 'REVIEW' CHECK(event_kind IN ('REVIEW','EVENT','RECOVERY')),
 urgency TEXT NOT NULL DEFAULT 'NORMAL' CHECK(urgency='NORMAL'),
 UNIQUE(owner_id,topic,novelty)
);
CREATE INDEX life_outreach_due ON life_outreach_candidate(owner_id,state,due_at);
CREATE TABLE life_habit_candidate (
 owner_id TEXT NOT NULL, topic TEXT NOT NULL, revision INTEGER NOT NULL,
 available_hour INTEGER NOT NULL, source TEXT NOT NULL, uncertainty TEXT NOT NULL,
 observed_at TEXT NOT NULL, expires_at TEXT NOT NULL, active INTEGER NOT NULL,
 PRIMARY KEY(owner_id,topic)
);
CREATE TABLE life_review_budget (
 owner_id TEXT NOT NULL, usage_day TEXT NOT NULL, calls INTEGER NOT NULL,
 PRIMARY KEY(owner_id,usage_day)
);
CREATE TABLE life_review_reply (
 id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, content_id TEXT NOT NULL REFERENCES life_review_content(id),
 text TEXT NOT NULL, created_at TEXT NOT NULL
);
-- 已发送副本在重置后独立保留；只读存档不参与生成、召回或主动策略。
CREATE TABLE life_review_archive (
 id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, frozen_text TEXT NOT NULL, archived_at TEXT NOT NULL
);
-- 不含正文和凭据的历史用途范围审计，设置覆盖或重置后仍可核对旧版本接收方。
CREATE TABLE life_review_grant (
 id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, settings_json TEXT NOT NULL,
 granted_at TEXT NOT NULL, revoked_at TEXT
);
CREATE INDEX life_review_grant_owner ON life_review_grant(owner_id,granted_at);
