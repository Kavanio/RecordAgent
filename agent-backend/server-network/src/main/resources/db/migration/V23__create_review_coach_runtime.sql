-- 长期复盘运行与反馈：显式授权后才运行；已提交模型的工作不自动重放。
CREATE TABLE review_job (
 id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, period TEXT NOT NULL,
 period_from TEXT NOT NULL, period_to TEXT NOT NULL, timezone TEXT NOT NULL,
 grant_id TEXT NOT NULL REFERENCES review_grant(id), grant_revision INTEGER NOT NULL,
 settings_revision INTEGER NOT NULL, connection_fingerprint TEXT NOT NULL,
 goals_fingerprint TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('PENDING','RUNNING','SUCCEEDED','DEGRADED','SKIPPED','UNKNOWN')),
 preview INTEGER NOT NULL DEFAULT 0, submitted_at TEXT, created_at TEXT NOT NULL,
 deadline TEXT NOT NULL, finished_at TEXT, result_json TEXT, message TEXT NOT NULL DEFAULT '',
 revision INTEGER NOT NULL DEFAULT 1, expires_at TEXT NOT NULL,
 UNIQUE(owner_id,period,period_from,period_to,timezone,grant_id,settings_revision,goals_fingerprint,preview)
);
CREATE UNIQUE INDEX review_owner_running ON review_job(owner_id) WHERE state='RUNNING';
CREATE INDEX review_job_pending ON review_job(state,created_at);
CREATE INDEX review_job_history ON review_job(owner_id,created_at DESC,id);
CREATE TABLE review_daily_budget (
 owner_id TEXT NOT NULL, budget_day TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY(owner_id,budget_day)
);
CREATE TABLE review_evidence (
 job_id TEXT NOT NULL REFERENCES review_job(id) ON DELETE CASCADE,
 evidence_id TEXT NOT NULL, snapshot_id TEXT NOT NULL REFERENCES review_snapshot(id) ON DELETE CASCADE,
 domain TEXT NOT NULL, metric_key TEXT NOT NULL, subject_id TEXT NOT NULL, currency TEXT NOT NULL,
 unit TEXT NOT NULL, value INTEGER NOT NULL, PRIMARY KEY(job_id,evidence_id)
);
CREATE TABLE review_action (
 id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, job_id TEXT REFERENCES review_job(id) ON DELETE SET NULL,
 topic_key TEXT NOT NULL, goal_id TEXT REFERENCES review_goal(id) ON DELETE SET NULL,
 title TEXT NOT NULL, rationale TEXT NOT NULL, criterion TEXT NOT NULL, method_id TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('PROPOSED','ACCEPTED','DECLINED','DEFERRED','COMPLETED','PARTIAL','NOT_DONE','UNKNOWN','ARCHIVED')),
 revision INTEGER NOT NULL DEFAULT 1, check_on TEXT, feedback TEXT NOT NULL DEFAULT '',
 created_at TEXT NOT NULL, updated_at TEXT NOT NULL, cooldown_until TEXT,
 CHECK(revision>0)
);
CREATE INDEX review_action_recall ON review_action(owner_id,state,check_on,updated_at);
CREATE TABLE review_observation (
 id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, job_id TEXT REFERENCES review_job(id) ON DELETE SET NULL,
 topic_key TEXT NOT NULL, content TEXT NOT NULL, uncertainty TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('CANDIDATE','CORRECTED','REJECTED')),
 revision INTEGER NOT NULL DEFAULT 1, correction TEXT NOT NULL DEFAULT '',
 created_at TEXT NOT NULL, expires_at TEXT NOT NULL
);
CREATE INDEX review_observation_recall ON review_observation(owner_id,state,expires_at);
CREATE TABLE review_memory_evidence (
 memory_id TEXT NOT NULL, job_id TEXT NOT NULL REFERENCES review_job(id) ON DELETE CASCADE,
 evidence_id TEXT NOT NULL, PRIMARY KEY(memory_id,job_id,evidence_id),
 FOREIGN KEY(job_id,evidence_id) REFERENCES review_evidence(job_id,evidence_id) ON DELETE CASCADE
);
CREATE TABLE review_feedback_request (
 owner_id TEXT NOT NULL, request_id TEXT NOT NULL, target_id TEXT NOT NULL, payload_fingerprint TEXT NOT NULL,
 response_json TEXT NOT NULL, created_at TEXT NOT NULL, PRIMARY KEY(owner_id,request_id)
);
CREATE TABLE review_task_preference (
 owner_id TEXT NOT NULL, task_id TEXT NOT NULL, enabled INTEGER NOT NULL CHECK(enabled IN(0,1)),
 revision INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(owner_id,task_id)
);
CREATE TABLE automation_review_delivery (
 run_id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, review_id TEXT REFERENCES review_job(id) ON DELETE SET NULL,
 state TEXT NOT NULL CHECK(state IN ('WAITING','FROZEN','DISABLED')),
 base_text TEXT NOT NULL, base_html TEXT NOT NULL, final_text TEXT, final_html TEXT,
 deadline TEXT NOT NULL, frozen_at TEXT, privacy_disabled INTEGER NOT NULL DEFAULT 0
);
